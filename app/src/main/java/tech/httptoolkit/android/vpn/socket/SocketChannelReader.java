package tech.httptoolkit.android.vpn.socket;

import androidx.annotation.NonNull;
import android.util.Log;

import tech.httptoolkit.android.vpn.ClientPacketWriter;
import tech.httptoolkit.android.vpn.Session;
import tech.httptoolkit.android.vpn.transport.ip.IPHeader;
import tech.httptoolkit.android.vpn.transport.tcp.TCPHeader;
import tech.httptoolkit.android.vpn.transport.tcp.TCPPacketFactory;
import tech.httptoolkit.android.vpn.transport.udp.UDPPacketFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;

import tech.httptoolkit.android.TagKt;
import org.distrinet.lanshield.crashreport.CrashReporterKt;

/**
 * Takes a session, and reads all available upstream data back into it.
 *
 * Used by the NIO thread, and run synchronously as part of that non-blocking loop.
 */
class SocketChannelReader {

	private final String TAG = TagKt.getTAG(this);

	private final ClientPacketWriter writer;

	// Report an oversized-UDP drop to Crashlytics at most once per reader, so a misbehaving peer
	// flooding jumbo datagrams can't flood the crash reporter (each drop is still Log.w'd).
	private volatile boolean alreadyReportedOversize = false;

	public SocketChannelReader(ClientPacketWriter writer) {
		this.writer = writer;
	}

	private void reportOversizeOnce(int packetLength) {
		if (alreadyReportedOversize) return;
		alreadyReportedOversize = true;
		CrashReporterKt.getCrashReporter().recordException(
				new RuntimeException("Dropped oversized UDP response packet: " + packetLength
						+ " bytes (MTU " + DataConst.MTU + ")"));
	}

	// When staged-but-unsent upstream bytes reach this, stop reading so the upstream TCP window
	// closes and the sender backs off, instead of pulling a multi-GB download into memory.
	private static final int STAGING_CAP = 2 * DataConst.MAX_RECEIVE_BUFFER_SIZE;

	public long read(Session session) {
		AbstractSelectableChannel channel = session.getChannel();
		long bytesRead = 0;
		if(channel instanceof SocketChannel) {
			bytesRead = readTCP(session);
		} else if(channel instanceof DatagramChannel){
			bytesRead = readUDP(session);
		} else {
			return 0;
		}

		// Resubscribe to reads, unless backpressure is holding us off (staging full): pumpToClient
		// re-subscribes once it drains below the cap.
		if (!(channel instanceof SocketChannel) || session.receivingStreamSize() < STAGING_CAP) {
			session.subscribeKey(SelectionKey.OP_READ);
		}

		if (session.isAbortingConnection()) {
			Log.d(TAG,"removing aborted connection -> "+ session);
			session.cancelKey();
			if (channel instanceof SocketChannel){
				try {
					SocketChannel socketChannel = (SocketChannel) channel;
					if (socketChannel.isConnected()) {
						socketChannel.close();
					}
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
			} else {
				try {
					DatagramChannel datagramChannel = (DatagramChannel) channel;
					if (datagramChannel.isConnected()) {
						datagramChannel.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			session.closeSession();
		}
		return bytesRead;
	}
	
	private long readTCP(@NonNull Session session) {
		if (session.isAbortingConnection()) {
			return 0;
		}

		long bytesRead = 0;
		SocketChannel channel = (SocketChannel) session.getChannel();
		ByteBuffer buffer = ByteBuffer.allocate(DataConst.MAX_RECEIVE_BUFFER_SIZE);
		int len;

		try {
			do {
				// Backpressure: stop reading once the staging buffer is full.
				if (session.receivingStreamSize() >= STAGING_CAP) {
					session.unsubscribeKey(SelectionKey.OP_READ);
					break;
				}
				len = channel.read(buffer);
				if (len > 0) { //-1 mean it reach the end of stream
					sendToRequester(buffer, len, session);
					buffer.clear();
					bytesRead += len;
				} else if (len == -1) {
					// EOF: defer the FIN to pumpToClient so it can't overtake unsent staged data.
					Log.d(TAG,"End of data from remote server, will FIN once drained: " + session);
					session.setUpstreamEof(true);
					pumpToClient(session);
				}
			} while (len > 0);
		}catch(NotYetConnectedException e){
			Log.e(TAG,"socket not connected");
		}catch(ClosedByInterruptException e){
			Log.e(TAG,"ClosedByInterruptException reading SocketChannel: "+ e.getMessage());
		}catch(ClosedChannelException e){
			Log.e(TAG,"ClosedChannelException reading SocketChannel: "+ e.getMessage());
		} catch (IOException e) {
			Log.e(TAG,"Error reading data from SocketChannel: "+ e.getMessage());
			session.setAbortingConnection(true);
		}
		return bytesRead;
	}
	
	private void sendToRequester(ByteBuffer buffer, int dataSize, @NonNull Session session){
		// Last piece of data is usually smaller than MAX_RECEIVE_BUFFER_SIZE. We use this as a
		// trigger to set PSH on the resulting TCP packet that goes to the VPN.
        session.setHasReceivedLastSegment(dataSize < DataConst.MAX_RECEIVE_BUFFER_SIZE);

		buffer.limit(dataSize);
		buffer.flip();
		// TODO should allocate new byte array?
		byte[] data = new byte[dataSize];
		System.arraycopy(buffer.array(), 0, data, 0, dataSize);
		session.addReceivedData(data);
		pumpToClient(session);
	}

	private int maxSegment(@NonNull Session session){
		// TODO What does 60 mean? Leaves room for IP + TCP options below the MSS.
		int max = session.getMaxSegmentSize() - 60;
		return max < 1 ? 1024 : max;
	}

	/**
	 * Send staged upstream data to the VPN client, keeping at most one client window in flight
	 * (clientWindow - (sendNext - sendUnack) bytes). Runs under the session monitor, from the NIO
	 * thread after a read and from the SessionHandler thread after a window-opening ACK. Sends the
	 * deferred FIN once the upstream is done and all staged data has drained. Sequence math is
	 * unsigned 32-bit so a multi-GB transfer (which wraps the sequence number) stays correct.
	 */
	void pumpToClient(@NonNull Session session){
		IPHeader ipHeader = session.getLastIpHeader();
		TCPHeader tcpheader = session.getLastTcpHeader();
		if (ipHeader == null || tcpheader == null) return;

		final int segMax = maxSegment(session);

		while (session.hasReceivedData()) {
			long inFlight = unsigned32(session.getSendNext() - session.getSendUnack());
			long room = session.getClientWindow() - inFlight;
			if (room <= 0) break;                                // window full (or zero window)
			int chunk = (int) Math.min(room, segMax);

			byte[] packetBody = session.getReceivedData(chunk);
			if (packetBody == null || packetBody.length == 0) break;

			long seq = unsigned32(session.getSendNext());
			session.setSendNext(session.getSendNext() + packetBody.length);

			boolean psh = session.hasReceivedLastSegment() && !session.hasReceivedData();
			writer.write(TCPPacketFactory.createResponsePacketData(ipHeader, tcpheader, packetBody,
					psh, session.getRecSequence(), seq,
					session.getTimestampSender(), session.getTimestampReplyto()));
		}

		if (session.isUpstreamEof() && !session.hasReceivedData() && !session.isAbortingConnection()) {
			sendFin(session);
			session.setAbortingConnection(true);
			return;
		}

		// Resume upstream reads if backpressure stopped them and staging has drained.
		if (!session.isUpstreamEof() && session.receivingStreamSize() < STAGING_CAP) {
			session.subscribeKey(SelectionKey.OP_READ);
		}
	}

	private static long unsigned32(long value){
		return value & 0xFFFFFFFFL;
	}
	private void sendFin(Session session){
		final IPHeader ipHeader = session.getLastIpHeader();
		final TCPHeader tcpheader = session.getLastTcpHeader();
		final byte[] data = TCPPacketFactory.createFinData(ipHeader, tcpheader,
				session.getRecSequence(), session.getSendNext(),
				session.getTimestampSender(), session.getTimestampReplyto());

		writer.write(data);
	}

	private long readUDP(Session session){
		DatagramChannel channel = (DatagramChannel) session.getChannel();
		long bytesRead = 0;
		ByteBuffer buffer = ByteBuffer.allocate(DataConst.MAX_RECEIVE_BUFFER_SIZE);
		int len;

		try {
			do{
				if (session.isAbortingConnection()) {
					break;
				}

				len = channel.read(buffer);
				if (len > 0) {
					buffer.limit(len);
					buffer.flip();

					//create UDP packet
					byte[] data = new byte[len];
					System.arraycopy(buffer.array(),0, data, 0, len);
					byte[] packetData = UDPPacketFactory.createResponsePacket(
							session.getLastIpHeader(), session.getLastUdpHeader(), data);

					if (packetData.length > DataConst.MTU) {
						// Can't be delivered over the TUN (MTU 1500) without IP fragmentation, which
						// the engine doesn't do. Drop it rather than hand the writer a jumbo packet.
						Log.w(TAG, "Dropping oversized UDP response (" + packetData.length
								+ " bytes > MTU " + DataConst.MTU + ")");
						reportOversizeOnce(packetData.length);
						buffer.clear();
						continue;
					}

					//write to client
					writer.write(packetData);

					Log.d(TAG,"SDR: sent " + len + " bytes to UDP client, packetData.length: "
							+ packetData.length);
					buffer.clear();
					bytesRead += len;
				}
			} while(len > 0);
		}catch(NotYetConnectedException ex){
			Log.e(TAG,"failed to read from unconnected UDP socket");
		} catch (IOException e) {
			Log.e(TAG,"Failed to read from UDP socket, aborting connection");
			session.setAbortingConnection(true);
		}
		return bytesRead;
	}
}
