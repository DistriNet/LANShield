/*
 *  Copyright 2014 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package tech.httptoolkit.android.vpn;

import static kotlinx.coroutines.CoroutineScopeKt.CoroutineScope;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import tech.httptoolkit.android.vpn.transport.ip.IPAddress;
import tech.httptoolkit.android.vpn.transport.ip.IPPacketFactory;
import tech.httptoolkit.android.vpn.transport.ip.IPHeader;
import tech.httptoolkit.android.vpn.socket.SocketNIODataService;
import tech.httptoolkit.android.vpn.transport.PacketHeaderException;
import tech.httptoolkit.android.vpn.transport.icmp.ICMPPacket;
import tech.httptoolkit.android.vpn.transport.icmp.ICMPPacketFactory;
import tech.httptoolkit.android.vpn.transport.ip.IPProtocol;
import tech.httptoolkit.android.vpn.transport.tcp.TCPHeader;
import tech.httptoolkit.android.vpn.transport.tcp.TCPPacketFactory;
import tech.httptoolkit.android.vpn.transport.udp.UDPHeader;
import tech.httptoolkit.android.vpn.transport.udp.UDPPacketFactory;
import tech.httptoolkit.android.vpn.util.PacketUtil;

import androidx.annotation.NonNull;
import android.util.Log;

import org.distrinet.lanshield.database.AppDatabase;
import org.distrinet.lanshield.database.model.LANFlow;
import org.distrinet.lanshield.vpnservice.DpiResult;
import org.distrinet.lanshield.vpnservice.VPNRunnable;

import tech.httptoolkit.android.TagKt;

/**
 * handle VPN client request and response. it create a new session for each VPN client.
 * @author Borey Sao
 * Date: May 22, 2014
 */
public class SessionHandler {
	private final String TAG = TagKt.getTAG(this);

	private final SessionManager manager;
	private final SocketNIODataService nioService;
	private final ClientPacketWriter writer;

	private final ExecutorService pingThreadpool;

	private final AppDatabase appDatabase;


	public SessionHandler(SessionManager manager, SocketNIODataService nioService, ClientPacketWriter writer, AppDatabase appDatabase) {
		this.manager = manager;
		this.nioService = nioService;
		this.writer = writer;
		this.appDatabase = appDatabase;

		// Pool of threads to synchronously proxy ICMP ping requests in the background. We need to
		// carefully limit these, or a ping flood can cause us big big problems.
		this.pingThreadpool = new ThreadPoolExecutor(
			1, 20, // 1 - 20 parallel pings max
			60L, TimeUnit.SECONDS,
			new SynchronousQueue<Runnable>(),
			new ThreadPoolExecutor.DiscardPolicy() // Replace running pings if there's too many
		);
	}

	/**
	 * Handle unknown raw IP packet data
	 *
	 * @param stream ByteBuffer to be read
	 */
	public void handlePacket(@NonNull ByteBuffer stream, String packageName) throws PacketHeaderException, IOException {
		stream.rewind();
		final IPHeader ipHeader = IPPacketFactory.createIPHeader(stream);

		if (ipHeader.getProtocol() == IPProtocol.TCP) {
			handleTCPPacket(stream, ipHeader, packageName);
		} else if (ipHeader.getProtocol() == IPProtocol.UDP) {
			handleUDPPacket(stream, ipHeader, packageName);
		} else if (ipHeader.getProtocol() == IPProtocol.ICMP) {
			handleICMPPacket(stream, ipHeader);
		} else {
			Log.w(TAG, "Unsupported IP protocol: " + ipHeader.getProtocol());
		}
	}

	private void handleUDPPacket(ByteBuffer clientPacketData, IPHeader ipHeader, String packageName) throws PacketHeaderException, IOException {
		UDPHeader udpheader = UDPPacketFactory.createUDPHeader(clientPacketData);

		Session session = manager.getSession(
			SessionProtocol.UDP,
			ipHeader.getDestinationIP(),
			udpheader.getDestinationPort(),
			ipHeader.getSourceIP(),
			udpheader.getSourcePort()
		);

		boolean newSession = session == null;

		if (session == null) {
			session = manager.createNewUDPSession(
				ipHeader.getDestinationIP(), udpheader.getDestinationPort(),
				ipHeader.getSourceIP(), udpheader.getSourcePort(),
					ipHeader.getTotalLength(), packageName, clientPacketData
			);
		}

		synchronized (session) {
			session.setLastIpHeader(ipHeader);
			session.setLastUdpHeader(udpheader);
			manager.addClientData(clientPacketData, session);
			session.setDataForSendingReady(true);

			// We don't register the session until it's fully populated (as above)
			if (newSession) nioService.registerSession(session);

			// Ping the NIO thread to write this, when the session is next writable
			session.subscribeKey(SelectionKey.OP_WRITE);
			nioService.refreshSelect(session);
		}

		manager.keepSessionAlive(session);
	}

	private void handleTCPPacket(ByteBuffer clientPacketData, IPHeader ipHeader, String packageName) throws PacketHeaderException, IOException {
		TCPHeader tcpheader = TCPPacketFactory.createTCPHeader(clientPacketData);
		int dataLength = clientPacketData.limit() - clientPacketData.position();
		IPAddress sourceIP = ipHeader.getSourceIP();
		IPAddress destinationIP = ipHeader.getDestinationIP();
		int sourcePort = tcpheader.getSourcePort();
		int destinationPort = tcpheader.getDestinationPort();

		if (tcpheader.isSYN()) {
			// 3-way handshake + create new session
			replySynAck(ipHeader,tcpheader, packageName);
		} else if(tcpheader.isACK()) {
			String key = Session.getSessionKey(SessionProtocol.TCP, destinationIP, destinationPort, sourceIP, sourcePort);
			Session session = manager.getSessionByKey(key);

			if (session == null) {
				Log.w(TAG, "Ack for unknown session: " + key);
				if (tcpheader.isFIN()) {
					sendLastAck(ipHeader, tcpheader);
				} else if (!tcpheader.isRST()) {
					sendRstPacket(ipHeader, tcpheader, dataLength);
				}

				return;
			}

			synchronized (session) {
				session.setLastIpHeader(ipHeader);
				session.setLastTcpHeader(tcpheader);
				LANFlow lanFlow = session.getFlow();


				//any data from client?
				if (dataLength > 0) {
					//accumulate data from client
					if (session.getRecSequence() == 0 || tcpheader.getSequenceNumber() >= session.getRecSequence()) {
						int addedLength = manager.addClientData(clientPacketData, session);
						//send ack to client only if new data was added
						sendAck(ipHeader, tcpheader, addedLength, session);
						if(lanFlow != null && (lanFlow.getDpiProtocol() == null || lanFlow.getDpiReport() == null)) {
							//Should not necessarily do this on first ack
							DpiResult dpiResult = VPNRunnable.Companion.doDpi(clientPacketData.array(), clientPacketData.limit(), clientPacketData.arrayOffset());
							if(dpiResult != null) {
								lanFlow.setDpiReport(dpiResult.getJsonBuffer());
								lanFlow.setDpiProtocol(dpiResult.getProtocolName());
								appDatabase.FlowDao().updateFlow(lanFlow);
							}
						}
					} else {
						sendAckForDisorder(ipHeader, tcpheader, dataLength);
					}
				} else {
					//an ack from client for previously sent data
					acceptAck(tcpheader, session);

					if (session.isClosingConnection()) {
						sendFinAck(ipHeader, tcpheader, session);
					} else if (session.isAckedToFin() && !tcpheader.isFIN()) {
						//the last ACK from client after FIN-ACK flag was sent
						manager.closeSession(SessionProtocol.TCP, destinationIP, destinationPort, sourceIP, sourcePort);
						Log.d(TAG, "got last ACK after FIN, session is now closed.");
					}
				}
				//received the last segment of data from vpn client
				if (tcpheader.isPSH()) {
					// Tell the NIO thread to immediately send data to the destination
					pushDataToDestination(session, tcpheader);
				} else if (tcpheader.isFIN()) {
					//fin from vpn client is the last packet
					//ack it
					Log.d(TAG, "FIN from vpn client, will ack it.");
					ackFinAck(ipHeader, tcpheader, session);
				} else if (tcpheader.isRST()) {
					resetTCPConnection(ipHeader, tcpheader);
				}

				if (!session.isAbortingConnection()) {
					manager.keepSessionAlive(session);
				}
			}
		} else if(tcpheader.isFIN()){
			//case client sent FIN without ACK
			Session session = manager.getSession(SessionProtocol.TCP, destinationIP, destinationPort, sourceIP, sourcePort);
			if(session == null)
				ackFinAck(ipHeader, tcpheader, null);
			else
				manager.keepSessionAlive(session);

		} else if(tcpheader.isRST()){
			resetTCPConnection(ipHeader, tcpheader);
		} else {
			Log.d(TAG,"unknown TCP flag");
			String str1 = PacketUtil.getOutput(ipHeader, tcpheader, clientPacketData.array());
			Log.d(TAG,">>>>>>>> Received from client <<<<<<<<<<");
			Log.d(TAG,str1);
			Log.d(TAG,">>>>>>>>>>>>>>>>>>>end receiving from client>>>>>>>>>>>>>>>>>>>>>");
		}
	}

	private void sendRstPacket(IPHeader ip, TCPHeader tcp, int dataLength){
		byte[] data = TCPPacketFactory.createRstData(ip, tcp, dataLength);

		writer.write(data);
		Log.d(TAG,"Sent RST Packet to client with dest => " +
				ip.getDestinationIP().toString() + ":" +
				tcp.getDestinationPort());
	}

	private void sendLastAck(IPHeader ip, TCPHeader tcp){
		byte[] data = TCPPacketFactory.createResponseAckData(ip, tcp, tcp.getSequenceNumber()+1);

		writer.write(data);
		Log.d(TAG,"Sent last ACK Packet to client with dest => " +
				ip.getDestinationIP().toString() + ":" +
				tcp.getDestinationPort());
	}

	private void ackFinAck(IPHeader ip, TCPHeader tcp, Session session){
		long ack = tcp.getSequenceNumber() + 1;
		long seq = tcp.getAckNumber();
		byte[] data = TCPPacketFactory.createFinAckData(ip, tcp, ack, seq, true, true);

		writer.write(data);
		if(session != null){
			session.cancelKey();
			manager.closeSession(session);
			Log.d(TAG,"ACK to client's FIN and close session => "+ip.getDestinationIP().toString()+":"+tcp.getDestinationPort()
					+"-"+ip.getSourceIP().toString()+":"+tcp.getSourcePort());
		}
	}
	private void sendFinAck(IPHeader ip, TCPHeader tcp, Session session){
		final long ack = tcp.getSequenceNumber();
		final long seq = tcp.getAckNumber();
		final byte[] data = TCPPacketFactory.createFinAckData(ip, tcp, ack, seq,true,false);
		final ByteBuffer stream = ByteBuffer.wrap(data);

		writer.write(data);
		Log.d(TAG,"00000000000 FIN-ACK packet data to vpn client 000000000000");
		IPHeader vpnip = null;
		try {
			vpnip = IPPacketFactory.createIPHeader(stream);
		} catch (PacketHeaderException e) {
			e.printStackTrace();
		}

		TCPHeader vpntcp = null;
		try {
			if (vpnip != null)
				vpntcp = TCPPacketFactory.createTCPHeader(stream);
		} catch (PacketHeaderException e) {
			e.printStackTrace();
		}

		if(vpnip != null && vpntcp != null){
			String sout = PacketUtil.getOutput(vpnip, vpntcp, data);
			Log.d(TAG,sout);
		}
		Log.d(TAG,"0000000000000 finished sending FIN-ACK packet to vpn client 000000000000");

		session.setSendNext(seq + 1);
		//avoid re-sending it, from here client should take care the rest
		session.setClosingConnection(false);
	}

	private void pushDataToDestination(Session session, TCPHeader tcp){
		session.setDataForSendingReady(true);
		session.setTimestampReplyto(tcp.getTimeStampSender());
		session.setTimestampSender((int)System.currentTimeMillis());

		// Ping the NIO thread to write this, when the session is next writable
		session.subscribeKey(SelectionKey.OP_WRITE);
		nioService.refreshSelect(session);
	}
	
	/**
	 * send acknowledgment packet to VPN client
	 * @param ipheader IP Header
	 * @param tcpheader TCP Header
	 * @param acceptedDataLength Data Length
	 * @param session Session
	 */
	private void sendAck(IPHeader ipheader, TCPHeader tcpheader, int acceptedDataLength, Session session){
		long acknumber = session.getRecSequence() + acceptedDataLength;
		session.setRecSequence(acknumber);
		byte[] data = TCPPacketFactory.createResponseAckData(ipheader, tcpheader, acknumber);

		writer.write(data);
	}

	/**
	 * resend the last acknowledgment packet to VPN client, e.g. when an unexpected out of order
	 * packet arrives.
	 * @param session Session
	 */
	private void resendAck(Session session){
		byte[] data = TCPPacketFactory.createResponseAckData(
				session.getLastIpHeader(),
				session.getLastTcpHeader(),
				session.getRecSequence()
		);
		writer.write(data);
	}

	private void sendAckForDisorder(IPHeader ipHeader, TCPHeader tcpheader, int acceptedDataLength) {
		long ackNumber = tcpheader.getSequenceNumber() + acceptedDataLength;
		Log.d(TAG,"sent disorder ack, ack# " + tcpheader.getSequenceNumber() +
				" + " + acceptedDataLength + " = " + ackNumber);
		byte[] data = TCPPacketFactory.createResponseAckData(ipHeader, tcpheader, ackNumber);

		writer.write(data);
	}

	/**
	 * acknowledge a packet.
	 * @param tcpHeader TCP Header
	 * @param session Session
	 */
	private void acceptAck(TCPHeader tcpHeader, Session session){
		boolean isCorrupted = PacketUtil.isPacketCorrupted(tcpHeader);

		session.setPacketCorrupted(isCorrupted);
		if (isCorrupted) {
			Log.e(TAG,"prev packet was corrupted, last ack# " + tcpHeader.getAckNumber());
		}

		if (
			tcpHeader.getAckNumber() > session.getSendUnack() ||
			tcpHeader.getAckNumber() == session.getSendNext()
		) {
			session.setAcked(true);

			session.setSendUnack(tcpHeader.getAckNumber());
			session.setRecSequence(tcpHeader.getSequenceNumber());
			session.setTimestampReplyto(tcpHeader.getTimeStampSender());
			session.setTimestampSender((int) System.currentTimeMillis());
		} else {
			Log.d(TAG,"Not Accepting ack# "+tcpHeader.getAckNumber() +" , it should be: "+session.getSendNext());
			Log.d(TAG,"Prev sendUnack: "+session.getSendUnack());
			session.setAcked(false);
		}
	}

	/**
	 * set connection as aborting so that background worker will close it.
	 * @param ip IP
	 * @param tcp TCP
	 */
	private void resetTCPConnection(IPHeader ip, TCPHeader tcp){
		Session session = manager.getSession(
			SessionProtocol.TCP,
			ip.getDestinationIP(), tcp.getDestinationPort(),
			ip.getSourceIP(), tcp.getSourcePort()
		);
		if(session != null){
			synchronized (session) {
				session.setAbortingConnection(true);
			}
		}
	}

	/**
	 * create a new client's session and SYN-ACK packet data to respond to client
	 * @param ip IP
	 * @param tcp TCP
	 */
	private void replySynAck(IPHeader ip, TCPHeader tcp, String packageName) throws IOException {
		Packet packet = TCPPacketFactory.createSynAckPacketData(ip, tcp);
		
		TCPHeader tcpheader = (TCPHeader) packet.getTransportHeader();
		
		Session session = manager.createNewTCPSession(
			ip.getDestinationIP(), tcp.getDestinationPort(),
			ip.getSourceIP(), tcp.getSourcePort(),
				ip.getTotalLength(), packageName
		);

		if (session.getLastIpHeader() != null) {
			// We have an existing session for this connection! We've somehow received a SYN
			// for an existing socket (or some kind of other race). We resend the last ACK
			// for this session, rejecting this SYN. Not clear why this happens, but it can.
			resendAck(session);
			return;
		}

		synchronized (session) {
			session.setMaxSegmentSize(tcpheader.getMaxSegmentSize());
			session.setSendUnack(tcpheader.getSequenceNumber());
			session.setSendNext(tcpheader.getSequenceNumber() + 1);
			//client initial sequence has been incremented by 1 and set to ack
			session.setRecSequence(tcpheader.getAckNumber());

			session.setLastIpHeader(ip);
			session.setLastTcpHeader(tcp);

			nioService.registerSession(session);

			writer.write(packet.getBuffer());
			Log.d(TAG,"Send SYN-ACK to client");
		}
	}

	private void handleICMPPacket(
		ByteBuffer clientPacketData,
		final IPHeader ipHeader) throws PacketHeaderException
	{
		final ICMPPacket requestPacket = ICMPPacketFactory.parseICMPPacket(ipHeader.getIpVersion(), clientPacketData);
		Log.d(TAG, "Got an ICMPv" + ipHeader.getIpVersion() + " packet, type " + requestPacket);

		if (requestPacket.isUnreachable()) {
			// This is a packet from the phone, telling somebody that a destination is unreachable.
			// Might be caused by issues on our end, but it's unclear what kind of issues. Regardless,
			// we can't send ICMP messages ourselves or react usefully, so we drop these silently.
			return;
		} else if (requestPacket.isRouterSolicitation()) {
			// This packet is often sent with IPv6 it seems
			return;
		} else if (!requestPacket.isPingRequest()) {
			// We only actually support outgoing ping packets. Loudly drop anything else:
			throw new PacketHeaderException(
				"Unknown ICMP type (" + requestPacket.type + "). Only echo requests are supported"
			);
		}

		pingThreadpool.execute(new Runnable() {
			@Override
			public void run() {
				try {
					if (!isReachable(ipHeader.getDestinationIP().toString())) {
						Log.d(TAG, "Failed ping, ignoring");
						return;
					}

					ICMPPacket response = ICMPPacketFactory.buildSuccessPacket(ipHeader.getIpVersion(), requestPacket);

					// Flip the address
					IPAddress destination = ipHeader.getDestinationIP();
					IPAddress source = ipHeader.getSourceIP();
					ipHeader.setSourceIP(destination);
					ipHeader.setDestinationIP(source);

					byte[] responseData = ICMPPacketFactory.packetToBuffer(ipHeader, response);

					Log.d(TAG, "Successful ping response");
					writer.write(responseData);
				} catch (PacketHeaderException e) {
					Log.w(TAG, "Handling ICMP failed with " + e.getMessage());
                }
			}

			private boolean isReachable(String ipAddress) {
				try {
					return InetAddress.getByName(ipAddress).isReachable(10000);
				} catch (IOException e) {
					return false;
				}
			}
		});
	}
}
