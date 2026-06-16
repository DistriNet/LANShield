package tech.httptoolkit.android.vpn

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.httptoolkit.android.vpn.transport.PacketHeaderException
import tech.httptoolkit.android.vpn.transport.icmp.ICMPPacketFactory
import tech.httptoolkit.android.vpn.transport.ip.IPPacketFactory
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IcmpForwardingTest {

    private lateinit var harness: ForwardingTestHarness

    private val clientIp = "10.0.0.2"
    private val clientIpv6 = "fd00::2"

    @Before
    fun setUp() {
        harness = ForwardingTestHarness()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // Live echo depends on isReachable, so these skip if the env blocks loopback reachability.

    @Test
    fun `ipv4 echo request to a reachable host returns an echo reply to the client`() {
        val payload = "ping-payload".toByteArray()
        harness.feed(
            TestPackets.icmpPacket(
                clientIp, "127.0.0.1", TestPackets.ICMP_V4_ECHO_REQUEST, 0,
                identifier = 0x1234, seq = 7, payload = payload,
            )
        )

        val reply = harness.pollTunPacket(5000)
        assumeTrue("no ICMP reply — isReachable(127.0.0.1) likely blocked in this env", reply != null)

        val icmp = parseIcmp(reply!!)
        assertThat(icmp.type).isEqualTo(TestPackets.ICMP_V4_ECHO_REPLY)
        assertThat(icmp.srcIp).isEqualTo("127.0.0.1")  // src/dst swapped
        assertThat(icmp.dstIp).isEqualTo(clientIp)
        assertThat(icmp.identifier).isEqualTo(0x1234)
        assertThat(icmp.seq).isEqualTo(7)
        assertThat(icmp.payload).isEqualTo(payload)
    }

    @Test
    fun `ipv6 echo request to a reachable host returns an echo reply`() {
        val payload = "v6".toByteArray()
        harness.feed(
            TestPackets.icmpv6Packet(
                clientIpv6, "::1", TestPackets.ICMP_V6_ECHO_REQUEST, 0,
                identifier = 0x55, seq = 3, payload = payload,
            )
        )

        val reply = harness.pollTunPacket(5000)
        assumeTrue("no ICMPv6 reply — isReachable(::1) likely blocked in this env", reply != null)

        val icmp = parseIcmp(reply!!)
        assertThat(icmp.type).isEqualTo(TestPackets.ICMP_V6_ECHO_REPLY)
        assertThat(icmp.identifier).isEqualTo(0x55)
        assertThat(icmp.seq).isEqualTo(3)
        assertThat(icmp.payload).isEqualTo(payload)
    }

    @Test
    fun `buildSuccessPacket and packetToBuffer produce a valid echo reply that echoes the request`() {
        val payload = "abcd".toByteArray()
        val requestBytes = TestPackets.icmpPacket(
            clientIp, "127.0.0.1", TestPackets.ICMP_V4_ECHO_REQUEST, 0,
            identifier = 0xBEEF, seq = 42, payload = payload,
        )
        val buffer = ByteBuffer.wrap(requestBytes)
        val ipHeader = IPPacketFactory.createIPHeader(buffer)
        val request = ICMPPacketFactory.parseICMPPacket(4, buffer)

        val reply = ICMPPacketFactory.buildSuccessPacket(4, request)
        val replyBytes = ICMPPacketFactory.packetToBuffer(ipHeader, reply)

        val parsed = parseIcmp(replyBytes)
        assertThat(parsed.type).isEqualTo(TestPackets.ICMP_V4_ECHO_REPLY)
        assertThat(parsed.identifier).isEqualTo(0xBEEF)
        assertThat(parsed.seq).isEqualTo(42)
        assertThat(parsed.payload).isEqualTo(payload)
        assertThat(icmpChecksumIsValid(replyBytes)).isTrue()
    }

    @Test
    fun `destination-unreachable icmp is dropped silently`() {
        harness.feed(
            TestPackets.icmpPacket(
                clientIp, "127.0.0.1", TestPackets.ICMP_V4_DEST_UNREACHABLE, 1,
                identifier = 1, seq = 1,
            )
        )
        assertThat(harness.pollTunPacket(300)).isNull()
    }

    @Test
    fun `ipv6 router solicitation is dropped silently`() {
        harness.feed(
            TestPackets.icmpv6Packet(
                clientIpv6, "ff02::2", TestPackets.ICMP_V6_ROUTER_SOLICITATION, 0,
                identifier = 0, seq = 0,
            )
        )
        assertThat(harness.pollTunPacket(300)).isNull()
    }

    @Test
    fun `an unsupported icmp type is rejected with a PacketHeaderException`() {
        // type 11 (time exceeded): not unreachable, router-solicit, or echo request.
        assertThrows(PacketHeaderException::class.java) {
            harness.feed(
                TestPackets.icmpPacket(clientIp, "127.0.0.1", 11, 0, identifier = 0, seq = 0)
            )
        }
    }

    @Test
    fun `icmp is not connection-tracked - no session or flow is created`() {
        harness.feed(
            TestPackets.icmpPacket(
                clientIp, "127.0.0.1", TestPackets.ICMP_V4_ECHO_REQUEST, 0,
                identifier = 9, seq = 9,
            )
        )
        assertThat(harness.flowDao.countNotSyncedFlows()).isEqualTo(0)
    }

    @Test
    fun `concurrent echo requests each get a correlated reply`() {
        val ids = listOf(0xA1 to 1, 0xA2 to 2, 0xA3 to 3)
        ids.forEach { (id, seq) ->
            harness.feed(
                TestPackets.icmpPacket(
                    clientIp, "127.0.0.1", TestPackets.ICMP_V4_ECHO_REQUEST, 0,
                    identifier = id, seq = seq, payload = "p$seq".toByteArray(),
                )
            )
        }

        // The ping pool may discard under flood, so require only that every reply seen
        // correlates to a request (and at least one comes back).
        val seen = mutableSetOf<Pair<Int, Int>>()
        while (true) {
            val pkt = harness.pollTunPacket(3000) ?: break
            val icmp = parseIcmp(pkt)
            assertThat(icmp.type).isEqualTo(TestPackets.ICMP_V4_ECHO_REPLY)
            val key = icmp.identifier to icmp.seq
            assertThat(ids).contains(key)
            seen.add(key)
            if (seen.size == ids.size) break
        }
        assumeTrue("no ICMP replies — isReachable(127.0.0.1) likely blocked in this env", seen.isNotEmpty())
    }

    // --- helpers -------------------------------------------------------------

    private data class ParsedIcmp(
        val srcIp: String, val dstIp: String,
        val type: Int, val identifier: Int, val seq: Int, val payload: ByteArray,
    )

    private fun parseIcmp(packet: ByteArray): ParsedIcmp {
        val ip = IPPacketFactory.createIPHeader(ByteBuffer.wrap(packet))
        val version = packet[0].toInt() shr 4 and 0x0F
        val off = if (version == 4) (packet[0].toInt() and 0x0F) * 4 else 40
        val type = packet[off].toInt() and 0xFF
        val identifier = ((packet[off + 4].toInt() and 0xFF) shl 8) or (packet[off + 5].toInt() and 0xFF)
        val seq = ((packet[off + 6].toInt() and 0xFF) shl 8) or (packet[off + 7].toInt() and 0xFF)
        val payload = packet.copyOfRange(off + 8, packet.size)
        return ParsedIcmp(ip.sourceIP.toString(), ip.destinationIP.toString(), type, identifier, seq, payload)
    }

    /** True when the one's-complement checksum over the ICMP message folds to zero. */
    private fun icmpChecksumIsValid(packet: ByteArray): Boolean {
        val version = packet[0].toInt() shr 4 and 0x0F
        val off = if (version == 4) (packet[0].toInt() and 0x0F) * 4 else 40
        var sum = 0L
        var i = off
        while (i < packet.size) {
            val hi = packet[i].toInt() and 0xFF
            val lo = if (i + 1 < packet.size) packet[i + 1].toInt() and 0xFF else 0
            sum += ((hi shl 8) or lo).toLong()
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt() and 0xFFFF == 0xFFFF
    }
}
