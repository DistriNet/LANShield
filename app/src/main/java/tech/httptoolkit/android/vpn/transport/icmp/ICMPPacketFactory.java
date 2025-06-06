package tech.httptoolkit.android.vpn.transport.icmp;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import tech.httptoolkit.android.vpn.transport.PacketHeaderException;
import tech.httptoolkit.android.vpn.transport.ip.IPHeader;

import static tech.httptoolkit.android.vpn.util.PacketUtil.calculateChecksum;

public class ICMPPacketFactory {

    public static ICMPPacket parseICMPPacket(int ipVersion, @NonNull ByteBuffer stream) throws PacketHeaderException {
        final short type = (short)(stream.get() & 0xFF);
        final short code = (short)(stream.get() & 0xFF);
        final int checksum = stream.getShort();

        final int identifier = stream.getShort();
        final int sequenceNumber = stream.getShort();

        final byte[] data = new byte[stream.remaining()];
        stream.get(data);

        return new ICMPPacket(ipVersion, type, code, checksum, identifier, sequenceNumber, data);
    }

    public static ICMPPacket buildSuccessPacket(int ipVersion, ICMPPacket requestPacket) throws PacketHeaderException {
        return new ICMPPacket(
            ipVersion,
            ipVersion == 4 ? ICMPPacket.TYPE_IPv4_REPLY : ICMPPacket.TYPE_IPv6_REPLY,
            0,
            0,
            requestPacket.identifier,
            requestPacket.sequenceNumber,
            requestPacket.data
        );
    }

    public static byte[] packetToBuffer(IPHeader ipHeader, ICMPPacket packet) throws PacketHeaderException {
        byte[] ipData = ipHeader.headerData();

        ByteArrayOutputStream icmpDataBuffer = new ByteArrayOutputStream();
        icmpDataBuffer.write(packet.type);
        icmpDataBuffer.write(packet.code);

        icmpDataBuffer.write(asShortBytes(0 /* checksum placeholder */), 0, 2);

        if (packet.isPingRequest() || packet.isPingReply()) {
            icmpDataBuffer.write(asShortBytes(packet.identifier), 0, 2);
            icmpDataBuffer.write(asShortBytes(packet.sequenceNumber), 0, 2);

            byte[] extraData = packet.data;
            icmpDataBuffer.write(extraData, 0, extraData.length);
        } else {
            throw new PacketHeaderException("Can't serialize unrecognized ICMP packet type");
        }

        byte[] icmpPacketData = icmpDataBuffer.toByteArray();
        byte[] checksum = calculateChecksum(icmpPacketData, 0, icmpPacketData.length);

        ByteBuffer resultBuffer = ByteBuffer.allocate(ipData.length + icmpPacketData.length);
        resultBuffer.put(ipData);
        resultBuffer.put(icmpPacketData);

        // Replace the checksum placeholder
        resultBuffer.position(ipData.length + 2);
        resultBuffer.put(checksum);
        resultBuffer.position(0);

        byte[] result = new byte[resultBuffer.remaining()];
        resultBuffer.get(result);
        return result;
    }

    private static byte[] asShortBytes(int value) {
        return ByteBuffer.allocate(2).putShort((short) value).array();
    }

}
