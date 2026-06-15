package tech.httptoolkit.android.vpn.socket;

import androidx.annotation.NonNull;
import android.util.Log;

import tech.httptoolkit.android.vpn.ClientPacketWriter;
import tech.httptoolkit.android.vpn.Session;
import tech.httptoolkit.android.vpn.transport.tcp.TCPPacketFactory;
import tech.httptoolkit.android.vpn.util.PacketUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Date;

import tech.httptoolkit.android.TagKt;

/**
 * Takes a VPN session, and writes all received data from it to the upstream channel.
 *
 * If any writes fail, it resubscribes to OP_WRITE, and tries again next time
 * that fires (as soon as the channel is ready for more data).
 *
 * Used by the NIO thread, and run synchronously as part of that non-blocking loop.
 */
public class SocketChannelWriter {
	private final String TAG = TagKt.getTAG(this);

	private final ClientPacketWriter writer;

	SocketChannelWriter(ClientPacketWriter writer) {
		this.writer = writer;
	}

	public long write(@NonNull Session session) {
		AbstractSelectableChannel channel = session.getChannel();
		long bytesWritten = 0;
		if (channel instanceof SocketChannel) {
			bytesWritten = writeTCP(session);
		} else if(channel instanceof DatagramChannel) {
			bytesWritten = writeUDP(session);
		} else {
			// We only ever create TCP & UDP channels, so this should never happen
			throw new IllegalArgumentException("Unexpected channel type: " + channel);
		}

		if (session.isAbortingConnection()) {
			Log.d(TAG,"removing aborted connection -> " + session);
			session.cancelKey();

			if (channel instanceof SocketChannel) {
				try {
					SocketChannel socketChannel = (SocketChannel) channel;
					if (socketChannel.isConnected()) {
						socketChannel.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else if (channel instanceof DatagramChannel) {
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
		return bytesWritten;
	}

	private long writeUDP(Session session) {
		long amountBytes = 0;
		try {
			amountBytes = writePendingUDPData(session);
			Date dt = new Date();
			session.connectionStartTime = dt.getTime();
		}catch(NotYetConnectedException ex2){
			session.setAbortingConnection(true);
			Log.e(TAG,"Error writing to unconnected-UDP server, will abort current connection: "+ex2.getMessage());
		} catch (IOException e) {
			session.setAbortingConnection(true);
			e.printStackTrace();
			Log.e(TAG,"Error writing to UDP server, will abort connection: "+e.getMessage());
		}
		return amountBytes;
	}
	
	private long writeTCP(Session session) {
		long amountBytes = 0;

		try {
			amountBytes = writePendingData(session);
		} catch (NotYetConnectedException ex) {
			Log.e(TAG,"failed to write to unconnected socket: " + ex.getMessage());
		} catch (IOException e) {
			Log.e(TAG,"Error writing to server: " + e);
			
			//close connection with vpn client
			byte[] rstData = TCPPacketFactory.createRstData(
					session.getLastIpHeader(), session.getLastTcpHeader(), 0);

			writer.write(rstData);

			//remove session
			Log.e(TAG,"failed to write to remote socket, aborting connection");
			session.setAbortingConnection(true);
		}
		return amountBytes;
	}

	/** TCP: a byte stream, so buffered bytes are concatenated and written as-is. */
	private long writePendingData(Session session) throws IOException {
		if (!session.hasDataToSend()) return 0;

		long totalBytesWritten = 0;
		SocketChannel channel = (SocketChannel) session.getChannel();

		byte[] data = session.getSendingData();
		ByteBuffer buffer = ByteBuffer.allocate(data.length);
		buffer.put(data);
		buffer.flip();

		while (buffer.hasRemaining()) {
			int bytesWritten = channel.write(buffer);

			if (bytesWritten == 0) {
				break;
			}
			totalBytesWritten += bytesWritten;
		}

		if (buffer.hasRemaining()) {
			// The channel's own buffer is full, so we have to save this for later.
			Log.i(TAG, buffer.remaining() + " bytes unwritten for " + channel.toString());

			// Put the remaining data from the buffer back into the session
			session.setSendingData(buffer.compact());

			// Subscribe to WRITE events, so we know when this is ready to resume.
			session.subscribeKey(SelectionKey.OP_WRITE);
		} else {
			// All done, all good -> wait until the next TCP PSH packet
			session.setDataForSendingReady(false);

			// We don't need to know about WRITE events any more, we've written all our data.
			// This is safe from races with new data, due to the session lock in NIO.
			session.unsubscribeKey(SelectionKey.OP_WRITE);
		}
		return totalBytesWritten;
	}

	/**
	 * UDP: a datagram protocol, so each queued datagram must be written with its own
	 * channel.write() to preserve message boundaries. We send one datagram per write cycle
	 * and resubscribe to OP_WRITE while more remain, mirroring the TCP backpressure pattern.
	 */
	private long writePendingUDPData(Session session) throws IOException {
		byte[] datagram = session.pollSendingDatagram();
		if (datagram == null) {
			session.setDataForSendingReady(false);
			session.unsubscribeKey(SelectionKey.OP_WRITE);
			return 0;
		}

		DatagramChannel channel = (DatagramChannel) session.getChannel();
		// A connected non-blocking DatagramChannel writes the whole datagram or nothing
		// (0 when the send buffer is full); it never sends a partial datagram.
		int bytesWritten = channel.write(ByteBuffer.wrap(datagram));

		if (bytesWritten == 0) {
			// Not ready yet: put the datagram back and resume on the next OP_WRITE.
			session.requeueSendingDatagram(datagram);
			session.subscribeKey(SelectionKey.OP_WRITE);
			return 0;
		}

		if (session.hasDataToSend()) {
			// More datagrams queued -> come back for the next one.
			session.subscribeKey(SelectionKey.OP_WRITE);
		} else {
			session.setDataForSendingReady(false);
			session.unsubscribeKey(SelectionKey.OP_WRITE);
		}
		return bytesWritten;
	}
}
