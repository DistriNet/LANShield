package org.distrinet.lanshield.vpnservice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer

/** Pure-JVM tests for [IPHeader] parsing of raw IPv4/IPv6 packets. */
class IPHeaderTest {

    private fun buffer(vararg bytes: Int): ByteBuffer {
        val arr = ByteArray(bytes.size) { (bytes[it] and 0xFF).toByte() }
        return ByteBuffer.wrap(arr)
    }

    /** 28-byte IPv4 UDP packet from 10.0.0.1:12345 to 8.8.8.8:53. */
    private fun ipv4UdpDnsPacket() = buffer(
        0x45, 0x00, 0x00, 0x1C,   // version/IHL, DSCP, total length = 28
        0x00, 0x00, 0x00, 0x00,   // id, flags/frag
        0x40, 0x11, 0x00, 0x00,   // ttl, protocol = 17 (UDP), checksum
        0x0A, 0x00, 0x00, 0x01,   // src 10.0.0.1
        0x08, 0x08, 0x08, 0x08,   // dst 8.8.8.8
        0x30, 0x39, 0x00, 0x35,   // src port 12345, dst port 53
        0x00, 0x08, 0x00, 0x00,   // udp length, checksum
    )

    @Test
    fun `parses ipv4 udp packet`() {
        val header = IPHeader(ipv4UdpDnsPacket())
        assertThat(header.ipVersion()).isEqualTo(4)
        assertThat(header.protocolNumberAsString()).isEqualTo("UDP")
        assertThat(header.source.address).isEqualTo(InetAddress.getByName("10.0.0.1"))
        assertThat(header.source.port).isEqualTo(12345)
        assertThat(header.destination.address).isEqualTo(InetAddress.getByName("8.8.8.8"))
        assertThat(header.destination.port).isEqualTo(53)
        assertThat(header.size).isEqualTo(28)
        assertThat(header.hasPayloadForDpi()).isTrue()  // UDP always has payload for DPI
    }

    /** 40-byte IPv4 TCP packet; total-length field claims 60 bytes (i.e. a payload). */
    private fun ipv4TcpPacket(totalLength: Int) = buffer(
        0x45, 0x00, (totalLength shr 8) and 0xFF, totalLength and 0xFF,
        0x00, 0x00, 0x00, 0x00,
        0x40, 0x06, 0x00, 0x00,   // protocol = 6 (TCP)
        0xC0, 0xA8, 0x01, 0x02,   // src 192.168.1.2
        0x01, 0x01, 0x01, 0x01,   // dst 1.1.1.1
        0x9C, 0x40, 0x01, 0xBB,   // src port 40000, dst port 443
        0x00, 0x00, 0x00, 0x00,   // seq
        0x00, 0x00, 0x00, 0x00,   // ack
        0x50, 0x00, 0x00, 0x00,   // data offset = 5 words (20 bytes)
        0x00, 0x00, 0x00, 0x00,   // checksum, urgent
    )

    @Test
    fun `parses ipv4 tcp packet with payload`() {
        val header = IPHeader(ipv4TcpPacket(totalLength = 60))
        assertThat(header.protocolNumberAsString()).isEqualTo("TCP")
        assertThat(header.source.port).isEqualTo(40000)
        assertThat(header.destination.port).isEqualTo(443)
        assertThat(header.size).isEqualTo(60)
        assertThat(header.hasPayloadForDpi()).isTrue()
    }

    @Test
    fun `tcp packet with no payload has nothing for dpi`() {
        // total length equals header lengths (20 + 20) -> no payload
        val header = IPHeader(ipv4TcpPacket(totalLength = 40))
        assertThat(header.hasPayloadForDpi()).isFalse()
    }

    /** 48-byte IPv6 UDP packet from ::1 to the ff02::1 multicast group. */
    private fun ipv6UdpPacket() = buffer(
        0x60, 0x00, 0x00, 0x00,   // version 6
        0x00, 0x08, 0x11, 0x40,   // payload length = 8, next header = 17 (UDP), hop limit
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,   // src ::1
        0xFF, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,   // dst ff02::1
        0x14, 0xE9, 0x00, 0x35,   // src port 5353, dst port 53
        0x00, 0x08, 0x00, 0x00,   // udp length, checksum
    )

    @Test
    fun `parses ipv6 udp packet`() {
        val header = IPHeader(ipv6UdpPacket())
        assertThat(header.ipVersion()).isEqualTo(6)
        assertThat(header.protocolNumberAsString()).isEqualTo("UDP")
        assertThat(header.source.address).isEqualTo(InetAddress.getByName("::1"))
        assertThat(header.source.port).isEqualTo(5353)
        assertThat(header.destination.port).isEqualTo(53)
        assertThat(header.size).isEqualTo(48)
        assertThat(header.destination.address.isMulticastAddress).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buffer shorter than minimum throws`() {
        IPHeader(buffer(0x45, 0x00, 0x00, 0x1C, 0x00, 0x00, 0x00, 0x00, 0x40, 0x11))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported ip version throws`() {
        // version 7, padded to the 24-byte minimum
        val bytes = IntArray(24) { 0 }.also { it[0] = 0x70 }
        IPHeader(buffer(*bytes))
    }
}
