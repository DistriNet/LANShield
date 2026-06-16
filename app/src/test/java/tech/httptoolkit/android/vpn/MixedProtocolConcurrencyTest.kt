package tech.httptoolkit.android.vpn

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.httptoolkit.android.vpn.transport.ip.IPAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Stress / consistency check: TCP downloads, UDP echo flows and ICMP pings run through the one
 * engine at the same time, exercising the shared NIO thread and session table under mixed load.
 * It asserts the protocols never bleed into each other — each TCP download in order on its own
 * port, each UDP reply on its own port, ICMP answered but never connection-tracked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MixedProtocolConcurrencyTest {

    private lateinit var harness: ForwardingTestHarness
    private val executor = Executors.newCachedThreadPool()

    private val clientIp = "10.0.0.2"
    private val peerIp = "127.0.0.1"
    private val mss = 1460
    private val tcpWindow = 16 * 1024
    private val tcpTotal = 64 * 1024

    @Before
    fun setUp() {
        harness = ForwardingTestHarness()
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        harness.close()
    }

    @Test
    fun `tcp udp and icmp flows run concurrently without crossing streams`() {
        val tcpPorts = listOf(54000, 54001, 54002)
        val udpPorts = listOf(42000, 42001, 42002)
        val pingIds = listOf(0xC1, 0xC2, 0xC3)

        val tcpFlows = tcpPorts.map { TcpFlow(it) }
        val tcpByPort = tcpFlows.associateBy { it.clientPort }
        val udpPeer = DatagramSocket(0, InetAddress.getByName(peerIp)).apply { soTimeout = 3000 }
        val udpPeerPort = udpPeer.localPort

        try {
            // Handshake the TCP flows first (clean SYN-ACK demux), then accept their sockets.
            tcpFlows.forEach { it.open() }
            tcpFlows.forEach { feedSyn(it) }
            repeat(tcpFlows.size) {
                val (ip, tcp) = harness.parseTcp(
                    harness.awaitTunPacketMatching { val (_, t) = harness.parseTcp(it); t.isSYN && t.isACK }
                )
                val flow = tcpByPort.getValue(tcp.destinationPort)
                assertThat(ip.destinationIP.toString()).isEqualTo(clientIp)
                flow.serverIsn = tcp.sequenceNumber
                flow.expectedSeq = (tcp.sequenceNumber + 1) and 0xFFFFFFFFL
            }
            tcpFlows.forEach { feedAck(it, it.serverIsn + 1) }
            tcpFlows.forEach { it.accept() }

            // Now drive all three protocols at once: TCP server writes, UDP datagrams, ICMP pings.
            val echo = Thread {
                repeat(udpPorts.size) {
                    val rx = DatagramPacket(ByteArray(128), 128)
                    udpPeer.receive(rx)
                    udpPeer.send(DatagramPacket(rx.data, rx.length, rx.socketAddress))
                }
            }.apply { isDaemon = true; start() }

            tcpFlows.forEach { flow ->
                executor.submit {
                    runCatching { flow.accepted.getOutputStream().apply { write(ByteArray(tcpTotal)); flush() } }
                }
            }
            udpPorts.forEach { port -> harness.feed(udp(port, udpPeerPort, "u-$port")) }
            pingIds.forEach { id ->
                harness.feed(TestPackets.icmpPacket(clientIp, peerIp, TestPackets.ICMP_V4_ECHO_REQUEST, 0, id, 1))
            }

            // One drain loop classifies every captured packet by protocol and routes it.
            val udpReplies = HashMap<Int, String>()
            val icmpReplies = HashSet<Int>()
            var idle = 0
            while ((tcpFlows.any { !it.complete } || udpReplies.size < udpPorts.size)) {
                val pkt = harness.pollTunPacket(1000)
                if (pkt == null) {
                    if (++idle > 3) break
                    tcpFlows.filter { !it.complete }.forEach { feedAck(it, it.serverIsn + 1 + it.received); it.lastAck = it.received }
                    continue
                }
                idle = 0
                when (ipProtocol(pkt)) {
                    6 -> {
                        val (_, tcp) = harness.parseTcp(pkt)
                        val flow = tcpByPort.getValue(tcp.destinationPort)  // only TCP client ports
                        val len = tcpPayloadLength(pkt)
                        if (len <= 0) continue
                        assertThat(tcp.sequenceNumber and 0xFFFFFFFFL).isEqualTo(flow.expectedSeq)
                        flow.expectedSeq = (flow.expectedSeq + len) and 0xFFFFFFFFL
                        flow.received += len
                        if (flow.received - flow.lastAck >= flow.window / 2 || flow.received == tcpTotal.toLong()) {
                            feedAck(flow, flow.serverIsn + 1 + flow.received); flow.lastAck = flow.received
                        }
                    }
                    17 -> {
                        val (_, udp, payload) = harness.parseUdp(pkt)
                        assertThat(udpPorts).contains(udp.destinationPort)  // only UDP client ports
                        udpReplies[udp.destinationPort] = String(payload)
                    }
                    1 -> {
                        val off = (pkt[0].toInt() and 0x0F) * 4
                        assertThat(pkt[off].toInt() and 0xFF).isEqualTo(TestPackets.ICMP_V4_ECHO_REPLY)
                        icmpReplies.add(((pkt[off + 4].toInt() and 0xFF) shl 8) or (pkt[off + 5].toInt() and 0xFF))
                    }
                }
            }

            // TCP delivered in full and in order (ordering guarded by the seq checks above).
            tcpFlows.forEach { assertThat(it.received).isEqualTo(tcpTotal.toLong()) }
            assertThat(udpReplies).isEqualTo(udpPorts.associateWith { "u-$it" })
            // Any ICMP replies that arrived correlate only to our pings.
            assertThat(pingIds).containsAtLeastElementsIn(icmpReplies)

            // One session/flow per TCP and UDP flow; ICMP contributed none.
            tcpFlows.forEach { assertThat(harness.sessionByKey(tcpKey(it.clientPort, it.peerPort))).isNotNull() }
            udpPorts.forEach { assertThat(harness.sessionByKey(udpKey(it, udpPeerPort))).isNotNull() }
            assertThat(harness.flowDao.countNotSyncedFlows())
                .isEqualTo((tcpPorts.size + udpPorts.size).toLong())

            echo.join(2000)
        } finally {
            tcpFlows.forEach { it.close() }
            udpPeer.close()
        }
    }

    // --- helpers -------------------------------------------------------------

    private inner class TcpFlow(val clientPort: Int) {
        lateinit var server: ServerSocket
        var peerPort = 0
        lateinit var accepted: Socket
        private lateinit var acceptFuture: java.util.concurrent.Future<Socket>
        val window = tcpWindow
        var serverIsn = 0L
        var expectedSeq = 0L
        var received = 0L
        var lastAck = 0L
        val complete get() = received >= tcpTotal

        fun open() {
            server = ServerSocket(0, 50, InetAddress.getByName(peerIp))
            peerPort = server.localPort
            acceptFuture = executor.submit<Socket> { server.accept() }
        }

        fun accept() { accepted = acceptFuture.get(3, TimeUnit.SECONDS) }

        fun close() {
            runCatching { if (this::accepted.isInitialized) accepted.close() }
            runCatching { server.close() }
        }
    }

    private fun feedSyn(flow: TcpFlow) = harness.feed(
        TestPackets.tcpPacket(
            clientIp, flow.clientPort, peerIp, flow.peerPort,
            seq = 1000L, ack = 0, flags = TestPackets.SYN, mss = mss, windowSize = flow.window,
        )
    )

    private fun feedAck(flow: TcpFlow, ackNumber: Long) = harness.feed(
        TestPackets.tcpPacket(
            clientIp, flow.clientPort, peerIp, flow.peerPort,
            seq = 1001L, ack = ackNumber, flags = TestPackets.ACK, windowSize = flow.window,
        )
    )

    private fun udp(clientPort: Int, peerPort: Int, payload: String): ByteArray =
        TestPackets.udpPacket(clientIp, clientPort, peerIp, peerPort, payload.toByteArray())

    private fun ipProtocol(packet: ByteArray): Int = packet[9].toInt() and 0xFF

    private fun tcpKey(clientPort: Int, peerPort: Int): String = Session.getSessionKey(
        SessionProtocol.TCP,
        IPAddress(TestPackets.ip(peerIp)), peerPort,
        IPAddress(TestPackets.ip(clientIp)), clientPort,
    )

    private fun udpKey(clientPort: Int, peerPort: Int): String = Session.getSessionKey(
        SessionProtocol.UDP,
        IPAddress(TestPackets.ip(peerIp)), peerPort,
        IPAddress(TestPackets.ip(clientIp)), clientPort,
    )

    private fun tcpPayloadLength(packet: ByteArray): Int {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val dataOffset = ((packet[ihl + 12].toInt() shr 4) and 0x0F) * 4
        return totalLength - ihl - dataOffset
    }
}
