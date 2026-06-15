package tech.httptoolkit.android.vpn

import java.nio.ByteBuffer

/**
 * Builders for raw IPv4 TCP/UDP packets used to drive the forwarding engine in tests.
 *
 * The engine parses inbound packets but does NOT verify their checksums, so we leave
 * checksum fields zero. Length fields, however, must be correct (the parsers and the
 * session code rely on them).
 */
object TestPackets {

    // TCP flag bits
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    fun ip(dotted: String): ByteArray {
        val parts = dotted.split(".")
        require(parts.size == 4) { "Only IPv4 literals supported: $dotted" }
        return ByteArray(4) { parts[it].toInt().toByte() }
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

    /**
     * IPv4 TCP packet. When [mss] is non-null an MSS option is added (data offset 6),
     * otherwise a plain 20-byte TCP header (data offset 5) is used.
     */
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
}
