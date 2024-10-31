package tech.httptoolkit.android.vpn.transport.icmp;

import tech.httptoolkit.android.vpn.transport.PacketHeaderException;

public class ICMPPacket {
    // Two ICMP packets we can handle: simple ping & pong
    public static final short TYPE_IPv4_REQUEST = 8;
    public static final short TYPE_IPv4_REPLY = 0;
    public static final short TYPE_IPv6_REQUEST = 128;
    public static final short TYPE_IPv6_REPLY = 129;
    public static final short TYPE_IPv6_ROUTER_SOLICITATION = 133;

    // One very common packet we ignore: connection rejection. Unclear why this happens,
    // random incoming connections that the phone tries to reply to? Nothing we can do though,
    // as we can't forward ICMP onwards, and we can't usefully respond or react.
    public static final short TYPE_IPv4_DESTINATION_UNREACHABLE = 3;
    public static final short TYPE_IPv6_DESTINATION_UNREACHABLE = 1;

    public final int ipVersion;
    public final short type;
    final short code; // 0 for request, 0 for success, 0 - 15 for error subtypes

    final int checksum;
    final int identifier;
    final int sequenceNumber;

    final byte[] data;

    ICMPPacket(
        int ipVersion,
        int type,
        int code,
        int checksum,
        int identifier,
        int sequenceNumber,
        byte[] data
    ) throws PacketHeaderException {
        this.ipVersion = ipVersion;
        this.type = (short)type;
        this.code = (short)code;
        this.checksum = checksum;
        this.identifier = identifier;
        this.sequenceNumber = sequenceNumber;
        this.data = data;
    }

    public boolean isPingRequest() {
        if (ipVersion == 4) {
            return type == TYPE_IPv4_REQUEST;
        } else {
            return type == TYPE_IPv6_REQUEST;
        }
    }

    public boolean isPingReply() {
        if (ipVersion == 4) {
            return type == TYPE_IPv4_REPLY;
        } else {
            return type == TYPE_IPv6_REPLY;
        }
    }

    public boolean isUnreachable() {
        if (ipVersion == 4) {
            return type == TYPE_IPv4_DESTINATION_UNREACHABLE;
        } else {
            return type == TYPE_IPv6_DESTINATION_UNREACHABLE;
        }
    }

    public boolean isRouterSolicitation() {
        return ipVersion == 6 && type == TYPE_IPv6_ROUTER_SOLICITATION;
    }

    public String toString() {
        return "ICMP packet type " + (type & 0xFF) + "/" + (code & 0xFF) + " id:" + identifier +
                " seq:" + sequenceNumber + " and " + data.length + " bytes of data";
    }
}
