package tech.httptoolkit.android.vpn.transport.udp;

import androidx.annotation.NonNull;

import tech.httptoolkit.android.vpn.transport.ip.IPAddress;
import tech.httptoolkit.android.vpn.transport.PacketHeaderException;
import tech.httptoolkit.android.vpn.transport.ip.IPHeader;
import tech.httptoolkit.android.vpn.util.PacketUtil;

import java.nio.ByteBuffer;

public class UDPPacketFactory {

	public static UDPHeader createUDPHeader(@NonNull ByteBuffer stream) throws PacketHeaderException{
		if ((stream.remaining()) < 8){
			throw new PacketHeaderException("Minimum UDP header is 8 bytes.");
		}
		final int srcPort = stream.getShort() & 0xffff;
		final int destPort = stream.getShort() & 0xffff;
		final int length = stream.getShort() & 0xffff;
		final int checksum = stream.getShort();

		return new UDPHeader(srcPort, destPort, length, checksum);
	}

	public static UDPHeader copyHeader(UDPHeader header){
		return new UDPHeader(header.getSourcePort(), header.getDestinationPort(),
				header.getLength(), header.getChecksum());
	}
	/**
	 * create packet data for responding to vpn client
	 * @param ip IPv4Header sent from VPN client, will be used as the template for response
	 * @param udp UDPHeader sent from VPN client
	 * @param packetData packet data to be sent to client
	 * @return array of byte
	 */
	public static byte[] createResponsePacket(IPHeader ip, UDPHeader udp, byte[] packetData){
		byte[] buffer;
		int udpLen = 8;
		if(packetData != null){
			udpLen += packetData.length;
		}
		int srcPort = udp.getDestinationPort();
		int destPort = udp.getSourcePort();
		short checksum = 0;

		IPHeader ipHeader = ip.clone();

		IPAddress srcIp = ip.getDestinationIP();
		IPAddress destIp = ip.getSourceIP();
		ipHeader.setSourceIP(srcIp);
		ipHeader.setDestinationIP(destIp);

		//ip's length is the length of the entire packet => IP header length + UDP header length (8) + UDP body length
		ipHeader.setPayloadLength(udpLen);
		int totalLength = ipHeader.getTotalLength();
		
		buffer = new byte[totalLength];
		byte[] ipData = ipHeader.headerData();

		// clear IP checksum
		ipData[10] = ipData[11] = 0;

		//calculate checksum for IP header
		byte[] ipChecksum = PacketUtil.calculateChecksum(ipData, 0, ipData.length);
		//write result of checksum back to buffer
		System.arraycopy(ipChecksum, 0, ipData, 10, 2);
		System.arraycopy(ipData, 0, buffer, 0, ipData.length);
		
		//copy UDP header to buffer
		int start = ipData.length;
		byte[] intContainer = new byte[4];
		PacketUtil.writeIntToBytes(srcPort, intContainer, 0);
		//extract the last two bytes of int value
		System.arraycopy(intContainer,2,buffer,start,2);
		start += 2;
		
		PacketUtil.writeIntToBytes(destPort, intContainer, 0);
		System.arraycopy(intContainer, 2, buffer, start, 2);
		start += 2;
		
		PacketUtil.writeIntToBytes(udpLen, intContainer, 0);
		System.arraycopy(intContainer, 2, buffer, start, 2);
		start += 2;
		
		PacketUtil.writeIntToBytes(checksum, intContainer, 0);
		System.arraycopy(intContainer, 2, buffer, start, 2);
		start += 2;
		
		//now copy udp data
		if (packetData != null) {
			System.arraycopy(packetData, 0, buffer, start, packetData.length);
		}

		return buffer;
	}
	
}//end
