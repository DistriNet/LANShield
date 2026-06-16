package tech.httptoolkit.android.vpn

import tech.httptoolkit.android.vpn.util.PacketUtil
import java.net.InetAddress
import java.nio.ByteBuffer

object TestPackets {

    // TCP flag bits
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    const val ICMP_V4_ECHO_REQUEST = 8
    const val ICMP_V4_ECHO_REPLY = 0
    const val ICMP_V4_DEST_UNREACHABLE = 3
    const val ICMP_V6_ECHO_REQUEST = 128
    const val ICMP_V6_ECHO_REPLY = 129
    const val ICMP_V6_ROUTER_SOLICITATION = 133

    fun ip(dotted: String): ByteArray {
        val parts = dotted.split(".")
        require(parts.size == 4) { "Only IPv4 literals supported: $dotted" }
        return ByteArray(4) { parts[it].toInt().toByte() }
    }

    /** 16-byte IPv6 address from a literal (e.g. "::1", "fd00::2"). */
    fun ipv6(literal: String): ByteArray {
        val bytes = InetAddress.getByName(literal).address
        require(bytes.size == 16) { "Not an IPv6 literal: $literal" }
        return bytes
    }

    private fun ByteArray.putShort(offset: Int, value: Int) {
        this[offset] = ((value ushr 8) and 0xFF).toByte()
        this[offset + 1] = (value and 0xFF).toByte()
    }

    private fun ByteArray.putInt(offset: Int, value: Long) {
        this[offset] = ((value ushr 24) and 0xFF).toByte()
        this[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        this[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        this[offset + 3] = (value and 0xFF).toByte()
    }

    /** 20-byte IPv4 header into [buf]; totalLength = full packet length, protocol 6/17. */
    private fun writeIpv4Header(
        buf: ByteArray, totalLength: Int, protocol: Int, src: ByteArray, dst: ByteArray,
    ) {
        buf[0] = 0x45            // version 4, IHL 5
        buf[1] = 0
        buf.putShort(2, totalLength)
        buf.putShort(4, 0)       // identification
        buf.putShort(6, 0)       // flags / fragment offset
        buf[8] = 64              // TTL
        buf[9] = protocol.toByte()
        buf.putShort(10, 0)      // header checksum (not verified by the engine)
        System.arraycopy(src, 0, buf, 12, 4)
        System.arraycopy(dst, 0, buf, 16, 4)
    }

    fun udpPacket(
        srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray,
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val buf = ByteArray(total)
        writeIpv4Header(buf, total, 17, ip(srcIp), ip(dstIp))
        buf.putShort(20, srcPort)
        buf.putShort(22, dstPort)
        buf.putShort(24, 8 + payload.size)   // UDP length
        buf.putShort(26, 0)                  // UDP checksum (optional)
        System.arraycopy(payload, 0, buf, 28, payload.size)
        return buf
    }

    fun tcpPacket(
        srcIp: String, srcPort: Int, dstIp: String, dstPort: Int,
        seq: Long, ack: Long, flags: Int, payload: ByteArray = ByteArray(0), mss: Int? = null,
        windowSize: Int = 65535,
    ): ByteArray {
        val optionBytes = if (mss != null) 4 else 0          // MSS option = kind(1)+len(1)+value(2)
        val tcpHeaderLen = 20 + optionBytes
        val dataOffsetWords = tcpHeaderLen / 4
        val total = 20 + tcpHeaderLen + payload.size
        val buf = ByteArray(total)
        writeIpv4Header(buf, total, 6, ip(srcIp), ip(dstIp))

        val t = 20  // TCP header start
        buf.putShort(t, srcPort)
        buf.putShort(t + 2, dstPort)
        buf.putInt(t + 4, seq)
        buf.putInt(t + 8, ack)
        buf[t + 12] = (dataOffsetWords shl 4).toByte()  // data offset, NS=0
        buf[t + 13] = flags.toByte()
        buf.putShort(t + 14, windowSize)   // window size
        buf.putShort(t + 16, 0)       // checksum (not verified)
        buf.putShort(t + 18, 0)       // urgent pointer
        if (mss != null) {
            buf[t + 20] = 0x02        // MSS option kind
            buf[t + 21] = 0x04        // length
            buf.putShort(t + 22, mss)
        }
        System.arraycopy(payload, 0, buf, t + tcpHeaderLen, payload.size)
        return buf
    }

    /** Tail [n] bytes of a captured packet as a String (the transport payload). */
    fun payloadString(packet: ByteArray, n: Int): String =
        String(packet, packet.size - n, n)

    /** IPv4 ICMP packet (protocol 1): 8-byte ICMP header + [payload], with a real checksum. */
    fun icmpPacket(
        srcIp: String, dstIp: String, type: Int, code: Int,
        identifier: Int, seq: Int, payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val icmpLen = 8 + payload.size
        val total = 20 + icmpLen
        val buf = ByteArray(total)
        writeIpv4Header(buf, total, 1, ip(srcIp), ip(dstIp))

        val i = 20
        buf[i] = type.toByte()
        buf[i + 1] = code.toByte()
        buf.putShort(i + 4, identifier)
        buf.putShort(i + 6, seq)
        System.arraycopy(payload, 0, buf, i + 8, payload.size)

        val checksum = PacketUtil.calculateChecksum(buf, i, icmpLen)
        buf[i + 2] = checksum[0]
        buf[i + 3] = checksum[1]
        return buf
    }

    /** IPv6 ICMP packet (next header 58): 40-byte IPv6 header + the same ICMP layout. */
    fun icmpv6Packet(
        srcIp: String, dstIp: String, type: Int, code: Int,
        identifier: Int, seq: Int, payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val icmpLen = 8 + payload.size
        val total = 40 + icmpLen
        val buf = ByteArray(total)
        writeIpv6Header(buf, icmpLen, 58, ipv6(srcIp), ipv6(dstIp))

        val i = 40
        buf[i] = type.toByte()
        buf[i + 1] = code.toByte()
        buf.putShort(i + 4, identifier)
        buf.putShort(i + 6, seq)
        System.arraycopy(payload, 0, buf, i + 8, payload.size)

        val checksum = PacketUtil.calculateChecksum(buf, i, icmpLen)
        buf[i + 2] = checksum[0]
        buf[i + 3] = checksum[1]
        return buf
    }

    private fun writeIpv6Header(
        buf: ByteArray, payloadLength: Int, nextHeader: Int, src: ByteArray, dst: ByteArray,
    ) {
        buf[0] = 0x60.toByte()   // version 6
        buf.putShort(4, payloadLength)
        buf[6] = nextHeader.toByte()
        buf[7] = 64              // hop limit
        System.arraycopy(src, 0, buf, 8, 16)
        System.arraycopy(dst, 0, buf, 24, 16)
    }
}
