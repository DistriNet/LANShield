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

package tech.httptoolkit.android.vpn.transport.ip;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import tech.httptoolkit.android.vpn.transport.PacketHeaderException;
import tech.httptoolkit.android.vpn.util.PacketUtil;

/**
 * Data structure for IPv4 header as defined in RFC 791.
 *
 * @author Borey Sao
 * Date: May 8, 2014
 */
public class IPv4Header extends IPHeader {
    //the size of the header (this also coincides with the offset to the data)
    private final byte internetHeaderLength;

    //Differentiated Services Code Point (DSCP) => 6 bits
    private byte dscpOrTypeOfService = 0;

    //Explicit Congestion Notification (ECN)
    private byte ecn = 0;

    //primarily used for uniquely identifying the group of fragments of a single IP datagram.
    private int identification = 0;

    //3 bits field used to control or identify fragments.
    //bit 0: Reserved; must be zero
    //bit 1: Don't Fragment (DF)
    //bit 2: More Fragments (MF)
    private byte flag = 0;
    private boolean mayFragment;
    private final boolean lastFragment;

    // The fragment offset for this IP datagram.
    private final short fragmentOffset;

    //This field limits a datagram's lifetime
    //It is specified in seconds, but time intervals less than 1 second are rounded up to 1
    private byte timeToLive = 0;

    private byte ipv4_protocol;

    //for error-checking of the header
    private int headerChecksum = 0;

    /**
     * create a new IPv4 Header
     *
     * @param ipVersion            the first header field in an IP packet. It is four-bit. For IPv4, this has a value of 4.
     * @param internetHeaderLength the second field (four bits) is the IP header length (from 20 to 60 bytes)
     * @param dscpOrTypeOfService  type of service
     * @param ecn                  Explicit Congestion Notification
     * @param payloadLength        Length of the payload after the IP header
     * @param identification       primarily used for uniquely identifying the group of fragments of a single IP datagram
     * @param mayFragment          bit number 1 of Flag. For DF (Don't Fragment)
     * @param lastFragment         bit number 2 of Flag. For MF (More Fragment)
     * @param fragmentOffset       13 bits long and specifies the offset of a particular fragment relative to the beginning of
     *                             the original unfragmented IP datagram.
     * @param timeToLive           8 bits field for preventing datagrams from persisting.
     * @param ipv4_protocol        The IPv4 'next-protocol' header value (e.g. TCP or UDP)
     * @param headerChecksum       16-bits field used for error-checking of the header
     * @param sourceIP             IPv4 address of sender.
     * @param destinationIP        IPv4 address of receiver.
     */
    public IPv4Header(byte ipVersion, byte internetHeaderLength,
                      byte dscpOrTypeOfService, byte ecn, int payloadLength,
                      int identification, boolean mayFragment,
                      boolean lastFragment, short fragmentOffset,
                      byte timeToLive, byte ipv4_protocol, int headerChecksum,
                      IPAddress sourceIP, IPAddress destinationIP) {
        this.ipVersion = ipVersion;
        this.internetHeaderLength = internetHeaderLength;
        this.dscpOrTypeOfService = dscpOrTypeOfService;
        this.ecn = ecn;
        this.payloadLength = payloadLength;
        this.identification = identification;
        this.mayFragment = mayFragment;
        if (mayFragment) {
            this.flag |= 0x40;
        }
        this.lastFragment = lastFragment;
        if (lastFragment) {
            this.flag |= 0x20;
        }
        this.fragmentOffset = fragmentOffset;
        this.timeToLive = timeToLive;
        setIPv4Protocol(ipv4_protocol);
        this.headerChecksum = headerChecksum;
        this.sourceIP = sourceIP.clone();
        this.destinationIP = destinationIP.clone();
    }

    public IPv4Header(@NonNull ByteBuffer stream) throws PacketHeaderException {
        //avoid Index out of range
        if (stream.remaining() < 20) {
            throw new PacketHeaderException("Minimum IPv4 header is 20 bytes. There are less "
                    + "than 20 bytes from start position to the end of array.");
        }

        final byte versionAndHeaderLength = stream.get();
        ipVersion = (byte) (versionAndHeaderLength >> 4);
        if (ipVersion != 0x04) {
            throw new PacketHeaderException("Invalid IPv4 header. IP version should be 4 but was " + ipVersion);
        }

        internetHeaderLength = (byte) (versionAndHeaderLength & 0x0F);
        if (stream.capacity() < internetHeaderLength * 4) {
            throw new PacketHeaderException("Not enough space in array for IP header");
        }

        final byte dscpAndEcn = stream.get();
        dscpOrTypeOfService = (byte) (dscpAndEcn >> 2);
        ecn = (byte) (dscpAndEcn & 0x03);
        payloadLength = (stream.getShort() & 0xFFFF) - internetHeaderLength;
        identification = stream.getShort();
        final short flagsAndFragmentOffset = stream.getShort();
        mayFragment = (flagsAndFragmentOffset & 0x4000) != 0;
        lastFragment = (flagsAndFragmentOffset & 0x2000) != 0;
        fragmentOffset = (short) (flagsAndFragmentOffset & 0x1FFF);
        timeToLive = stream.get();
        setIPv4Protocol(stream.get());
        headerChecksum = stream.getShort();
        sourceIP = new IPAddress(stream, 4); // XXX Check this
        destinationIP = new IPAddress(stream, 4);
        if (internetHeaderLength > 5) {
            // drop the IP option
            for (int i = 0; i < internetHeaderLength - 5; i++) {
                stream.getInt();
            }
        }
    }

    @Override
    public void setPayloadLength(int length) {
        super.setPayloadLength(length);
        // When this method is called, we are typically constructing a new packet,
        // and want to get a new IP identification field.
        identification = PacketUtil.getPacketId();
    }

    void setIPv4Protocol(byte ipv4_protocol) {
        this.ipv4_protocol = ipv4_protocol;

        if (this.ipv4_protocol == 6) {
            this.protocol = IPProtocol.TCP;
        } else if (this.ipv4_protocol == 17) {
            this.protocol = IPProtocol.UDP;
        } else if (this.ipv4_protocol == 1) {
            this.protocol = IPProtocol.ICMP;
        }
    }
    
    byte getInternetHeaderLength() {
        return internetHeaderLength;
    }

    public byte getDscpOrTypeOfService() {
        return dscpOrTypeOfService;
    }

    byte getEcn() {
        return ecn;
    }

    /**
     * total length of IP header in bytes.
     *
     * @return IP Header total length
     */
    public int getIPHeaderLength() {
        return (internetHeaderLength * 4);
    }

    public void calculateIpChecksum(byte[] buffer ) {
        byte[] zero = {0, 0};
        System.arraycopy(zero, 0, buffer, 10, 2);
        byte[] ipChecksum = PacketUtil.calculateChecksum(buffer, 0, this.getIPHeaderLength());
        System.arraycopy(ipChecksum, 0, buffer, 10, 2);
    }

    public ByteBuffer getTcpPseudoHeader(short tcplength) {
//        ByteBuffer buffer = ByteBuffer.allocate(tcplength + 4 + 4 * 2); //TODO double check this, also for IPv6
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 * 2);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(sourceIP.getBytes());
        buffer.put(destinationIP.getBytes());
        buffer.put((byte) 0);//reserved => 0
        buffer.put((byte) 6);//tcp protocol => 6
        buffer.putShort(tcplength);
        return buffer;
    }

    /**
     * total length of this packet in bytes including IP Header and body(TCP/UDP header + data)
     *
     * @return totalLength
     */
    public int getTotalLength() {
        return this.getIPHeaderLength() + this.getPayloadLength();
    }

    public int getIdentification() {
        return identification;
    }

    public byte getFlag() {
        return flag;
    }

    public boolean isMayFragment() {
        return mayFragment;
    }

    public boolean isLastFragment() {
        return lastFragment;
    }

    public short getFragmentOffset() {
        return fragmentOffset;
    }

    public byte getTimeToLive() {
        return timeToLive;
    }

    public int getHeaderChecksum() {
        return headerChecksum;
    }

    public void setIdentification(int identification) {
        this.identification = identification;
    }

    public void setMayFragment(boolean mayFragment) {
        this.mayFragment = mayFragment;
        if (mayFragment) {
            this.flag |= 0x40;
        } else {
            this.flag &= 0xBF;
        }
    }

    /**
     * make new instance of IPv4Header
     *
     * @return IPv4Header
     */
    public IPv4Header clone() {
        return new IPv4Header(this.getIpVersion(),
                this.getInternetHeaderLength(),
                this.getDscpOrTypeOfService(), this.getEcn(),
                this.getPayloadLength(), this.getIdentification(),
                this.isMayFragment(), this.isLastFragment(),
                this.getFragmentOffset(), this.getTimeToLive(),
                this.ipv4_protocol, this.getHeaderChecksum(),
                this.getSourceIP(), this.getDestinationIP());
    }

    /**
     * create IPv4 Header array of byte from a given IPv4Header object
     *
     * @return array of byte
     */
    public byte[] headerData() {
        final byte[] buffer = new byte[this.getIPHeaderLength()];

        buffer[0] = (byte) ((this.getInternetHeaderLength() & 0xF) | 0x40);
        buffer[1] = (byte) ((this.getDscpOrTypeOfService() << 2) & (this.getEcn() & 0xFF));
        buffer[2] = (byte) (this.getTotalLength() >> 8);
        buffer[3] = (byte) this.getTotalLength();
        buffer[4] = (byte) (this.getIdentification() >> 8);
        buffer[5] = (byte) this.getIdentification();

        //combine flags and partial fragment offset
        buffer[6] = (byte) (((this.getFragmentOffset() >> 8) & 0x1F) | this.getFlag());
        buffer[7] = (byte) this.getFragmentOffset();
        buffer[8] = this.getTimeToLive();
        buffer[9] = this.ipv4_protocol;
        buffer[10] = (byte) (this.getHeaderChecksum() >> 8);
        buffer[11] = (byte) this.getHeaderChecksum();

        //source ip address
        System.arraycopy(this.getSourceIP().getBytes(), 0, buffer, 12, 4);
        //destination ip address
        System.arraycopy(this.getDestinationIP().getBytes(), 0, buffer, 16, 4);

        return buffer;
    }
}
