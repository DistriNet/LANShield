package tech.httptoolkit.android.vpn

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.httptoolkit.android.vpn.transport.ip.IPHeader
import tech.httptoolkit.android.vpn.transport.ip.IPPacketFactory
import tech.httptoolkit.android.vpn.transport.tcp.TCPHeader
import tech.httptoolkit.android.vpn.transport.tcp.TCPPacketFactory
import tech.httptoolkit.android.vpn.transport.udp.UDPHeader
import tech.httptoolkit.android.vpn.transport.udp.UDPPacketFactory
import java.nio.ByteBuffer

class PacketFactoryRoundTripTest {

    private fun parseIpTcp(packet: ByteArray): Pair<IPHeader, TCPHeader> {
        val b = ByteBuffer.wrap(packet)
        return IPPacketFactory.createIPHeader(b) to TCPPacketFactory.createTCPHeader(b)
    }

    private fun parseIpUdp(packet: ByteArray): Triple<IPHeader, UDPHeader, ByteArray> {
        val b = ByteBuffer.wrap(packet)
        val ip = IPPacketFactory.createIPHeader(b)
        val udp = UDPPacketFactory.createUDPHeader(b)
        val payload = ByteArray(b.remaining()).also { b.get(it) }
        return Triple(ip, udp, payload)
    }

    private fun tailString(packet: ByteArray, n: Int) = String(packet, packet.size - n, n)

    @Test
    fun `parsing a crafted SYN recovers its fields and MSS option`() {
        val (ip, tcp) = parseIpTcp(
            TestPackets.tcpPacket("10.0.0.2", 50000, "8.8.8.8", 443, seq = 1000, ack = 0, flags = TestPackets.SYN, mss = 1460)
        )
        assertThat(ip.sourceIP.toString()).isEqualTo("10.0.0.2")
        assertThat(ip.destinationIP.toString()).isEqualTo("8.8.8.8")
        assertThat(tcp.sourcePort).isEqualTo(50000)
        assertThat(tcp.destinationPort).isEqualTo(443)
        assertThat(tcp.sequenceNumber).isEqualTo(1000)
        assertThat(tcp.isSYN).isTrue()
        assertThat(tcp.maxSegmentSize).isEqualTo(1460)
    }

    @Test
    fun `createSynAckPacketData flips endpoints and acks the client sequence`() {
        val (ip, tcp) = parseIpTcp(
            TestPackets.tcpPacket("10.0.0.2", 50000, "8.8.8.8", 443, seq = 1000, ack = 0, flags = TestPackets.SYN, mss = 1460)
        )
        val (ip2, synAck) = parseIpTcp(TCPPacketFactory.createSynAckPacketData(ip, tcp).buffer)

        assertThat(synAck.isSYN).isTrue()
        assertThat(synAck.isACK).isTrue()
        assertThat(synAck.ackNumber).isEqualTo(1001)
        assertThat(synAck.sourcePort).isEqualTo(443)
        assertThat(synAck.destinationPort).isEqualTo(50000)
        assertThat(ip2.sourceIP.toString()).isEqualTo("8.8.8.8")
        assertThat(ip2.destinationIP.toString()).isEqualTo("10.0.0.2")
    }

    @Test
    fun `createRstData produces a reset packet`() {
        val (ip, tcp) = parseIpTcp(
            TestPackets.tcpPacket("10.0.0.2", 50000, "8.8.8.8", 443, seq = 7, ack = 0, flags = TestPackets.SYN)
        )
        val (_, rst) = parseIpTcp(TCPPacketFactory.createRstData(ip, tcp, 0))
        assertThat(rst.isRST).isTrue()
    }

    @Test
    fun `createResponseAckData acknowledges the given number`() {
        val (ip, tcp) = parseIpTcp(
            TestPackets.tcpPacket("10.0.0.2", 50000, "8.8.8.8", 443, seq = 7, ack = 99, flags = TestPackets.ACK)
        )
        val (_, ack) = parseIpTcp(TCPPacketFactory.createResponseAckData(ip, tcp, 1234))
        assertThat(ack.isACK).isTrue()
        assertThat(ack.ackNumber).isEqualTo(1234)
    }

    @Test
    fun `createResponsePacketData carries the payload and push flag`() {
        val (ip, tcp) = parseIpTcp(
            TestPackets.tcpPacket("10.0.0.2", 50000, "8.8.8.8", 443, seq = 7, ack = 99, flags = TestPackets.ACK)
        )
        val packet = TCPPacketFactory.createResponsePacketData(ip, tcp, "DATA".toByteArray(), true, 5, 9, 0, 0)
        val (_, resp) = parseIpTcp(packet)
        assertThat(resp.isPSH).isTrue()
        assertThat(resp.sequenceNumber).isEqualTo(9)
        assertThat(resp.ackNumber).isEqualTo(5)
        assertThat(tailString(packet, 4)).isEqualTo("DATA")
    }

    @Test
    fun `udp response swaps endpoints and carries the payload`() {
        val (ip, udp, _) = parseIpUdp(
            TestPackets.udpPacket("10.0.0.2", 50000, "8.8.8.8", 53, "q".toByteArray())
        )
        val packet = UDPPacketFactory.createResponsePacket(ip, udp, "REPLY".toByteArray())
        val (ip2, udp2, payload) = parseIpUdp(packet)

        assertThat(ip2.sourceIP.toString()).isEqualTo("8.8.8.8")
        assertThat(ip2.destinationIP.toString()).isEqualTo("10.0.0.2")
        assertThat(udp2.sourcePort).isEqualTo(53)
        assertThat(udp2.destinationPort).isEqualTo(50000)
        assertThat(String(payload)).isEqualTo("REPLY")
    }
}
