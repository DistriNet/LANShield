package tech.httptoolkit.android.vpn.transport.ip;

import java.nio.ByteBuffer;

public abstract class IPHeader {
    protected byte ipVersion;
    protected int payloadLength;
    protected IPProtocol protocol;
    protected IPAddress sourceIP;
    protected IPAddress destinationIP;

    public byte getIpVersion() {
        return ipVersion;
    }

    public int getPayloadLength() {
        return this.payloadLength;
    }

    public void setPayloadLength(int length) {
        this.payloadLength = length;
    }

    public IPAddress getSourceIP() {
        return sourceIP;
    }

    public IPAddress getDestinationIP() {
        return destinationIP;
    }

    public void setSourceIP(IPAddress sourceIP) {
        this.sourceIP = sourceIP;
    }

    public void setDestinationIP(IPAddress destinationIP) {
        this.destinationIP = destinationIP;
    }

    public IPProtocol getProtocol() {
        return this.protocol;
    }

    // Note that IPv6 has no checksum but IPv4 does implement this
    public void calculateIpChecksum(byte[] buffer) { };

    public abstract ByteBuffer getTcpPseudoHeader(short tcplength);
    public abstract int getTotalLength();
    public abstract IPHeader clone();
    public abstract byte[] headerData();
}
