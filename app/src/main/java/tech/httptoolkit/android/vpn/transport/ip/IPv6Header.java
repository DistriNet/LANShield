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

/**
 * Data structure for IPv6 header.

 */
public class IPv6Header extends IPHeader {
    private byte trafficClass;
    private int flowLabel;
    private byte nextHeader;
    private byte hopLimit;

    public byte getTrafficClass() { return trafficClass; }
    public int getFlowLabel() { return flowLabel; }
    public byte getNextHeader() { return nextHeader; }
    public byte getHopLimit() { return hopLimit; }

    public IPv6Header(byte ipVersion, byte trafficClass, int flowLabel,
                      int payloadLength, byte nextHeader, byte hopLimit,
                      IPAddress sourceIP, IPAddress destinationIP){
        this.ipVersion = ipVersion;
        this.trafficClass = trafficClass;
        this.flowLabel = flowLabel;
        this.payloadLength = payloadLength;
        this.setNextHeader(nextHeader);
        this.hopLimit = hopLimit;

        this.sourceIP = sourceIP.clone();
        this.destinationIP = destinationIP.clone();
    }

    public void setNextHeader(byte nextHeader){
        this.nextHeader = nextHeader;

        if (this.nextHeader == 6)
            this.protocol = IPProtocol.TCP;
        else if (this.nextHeader == 17)
            this.protocol = IPProtocol.UDP;
        else if (this.nextHeader == 58)
            this.protocol = IPProtocol.ICMP;
    }

    public IPv6Header(@NonNull ByteBuffer stream) throws PacketHeaderException {
        //avoid Index out of range
        if (stream.remaining() < 40) {
            throw new PacketHeaderException("Minimum IPv6 header is 40 bytes. There are less "
                    + "than 40 bytes from start position to the end of array.");
        }

        final byte versionAndTrafficClass = stream.get();
        ipVersion = (byte) (versionAndTrafficClass >> 4);
        if (ipVersion != 0x06) {
            throw new PacketHeaderException("Invalid IPv6 header. IP version should be 6 but was " + ipVersion);
        }

        final byte trafficClassAndFlowLabel = stream.get();
        trafficClass = (byte)( ((versionAndTrafficClass & 0x0F) << 4) + (trafficClassAndFlowLabel >> 4) );

        flowLabel = (trafficClassAndFlowLabel << 16) + stream.getShort();

        payloadLength = stream.getShort();
        setNextHeader(stream.get());
        hopLimit = stream.get();

        sourceIP = new IPAddress(stream, 16);
        destinationIP = new IPAddress(stream, 16);
    }

    public ByteBuffer getTcpPseudoHeader(short tcplength) {
        ByteBuffer buffer = ByteBuffer.allocate(tcplength + 8 + 16 * 2);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(sourceIP.getBytes());
        buffer.put(destinationIP.getBytes());
        buffer.putInt(tcplength);
        buffer.put((byte) 0);//reserved => 0
        buffer.put((byte) 0);//reserved => 0
        buffer.put((byte) 0);//reserved => 0
        buffer.put((byte) 6);//tcp protocol => 6
        return buffer;
    }

    public int getTotalLength() {
        return 40 + payloadLength;
    }

    public IPv6Header clone() {
        return new IPv6Header(this.getIpVersion(),
                this.getTrafficClass(),
                this.getFlowLabel(), this.getPayloadLength(),
                this.getNextHeader(), this.getHopLimit(),
                this.getSourceIP(), this.getDestinationIP());
    }

    public byte[] headerData() {
        final byte[] buffer = new byte[40];

        buffer[0] = (byte) (((this.getIpVersion() & 0xF) << 4) | this.getTrafficClass());
        buffer[1] = (byte) (((this.getTrafficClass() & 0xF) << 4) | (this.getFlowLabel() & 0xF));
        buffer[2] = (byte) ((this.getFlowLabel() >> 8) & 0xFF);
        buffer[3] = (byte) (this.getFlowLabel() & 0xFF);
        buffer[4] = (byte) ((this.getPayloadLength() >> 8) & 0xFF);
        buffer[5] = (byte) (this.getPayloadLength() & 0xFF);
        buffer[6] = (byte) this.getNextHeader();
        buffer[7] = (byte) this.getHopLimit();

        //source ip address
        System.arraycopy(this.getSourceIP().getBytes(), 0, buffer, 8, 16);
        //destination ip address
        System.arraycopy(this.getDestinationIP().getBytes(), 0, buffer, 24, 16);

        return buffer;
    }
}
