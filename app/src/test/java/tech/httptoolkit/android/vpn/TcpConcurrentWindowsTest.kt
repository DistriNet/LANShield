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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drives many concurrent TCP downloads through the one engine, each advertising a *different*
 * receive window. All flows share one capture queue and are demultiplexed by destination port,
 * so the test also proves connection tracking keeps the streams separate: each flow is
 * delivered strictly in order, in full, with unacked data never exceeding its own window, and
 * a client port can be reused once the previous connection is torn down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TcpConcurrentWindowsTest {

    private lateinit var harness: ForwardingTestHarness
    private val executor = Executors.newCachedThreadPool()

    private val clientIp = "10.0.0.2"
    private val peerIp = "127.0.0.1"
    private val mss = 1460

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
    fun `concurrent downloads with distinct windows are each delivered in order within their window`() {
        // Distinct windows, all within the unscaled 16-bit TCP window field (>64K would need the
        // window-scale option, which these packets don't carry).
        val windows = listOf(2 * 1024, 4 * 1024, 8 * 1024, 16 * 1024, 32 * 1024, 65535)
        val total = 128 * 1024
        val flows = windows.mapIndexed { i, window -> Flow(clientPort = 51000 + i, window = window, total = total) }
        val byPort = flows.associateBy { it.clientPort }

        try {
            // Each flow gets its own loopback server, so an accepted socket maps to one flow.
            flows.forEach { it.open() }

            // Handshake all flows, demultiplexing the SYN-ACKs back to each by client port.
            flows.forEach { feedSyn(it) }
            repeat(flows.size) {
                val pkt = harness.awaitTunPacketMatching { val (_, t) = harness.parseTcp(it); t.isSYN && t.isACK }
                val (ip, tcp) = harness.parseTcp(pkt)
                val flow = byPort.getValue(tcp.destinationPort)
                assertThat(ip.destinationIP.toString()).isEqualTo(clientIp)
                flow.serverIsn = tcp.sequenceNumber
                flow.expectedSeq = (tcp.sequenceNumber + 1) and 0xFFFFFFFFL
            }
            flows.forEach { feedAck(it, it.serverIsn + 1) }
            flows.forEach { it.accept() }

            flows.forEach { flow ->
                executor.submit {
                    runCatching { flow.accepted.getOutputStream().apply { write(ByteArray(flow.total)); flush() } }
                }
            }

            // Drain loop, demuxing by client port. Ack a flow only at ~half its window so
            // in-flight grows toward (never past) the window; nudge idle flows in case the
            // engine is waiting on an ACK.
            var idle = 0
            while (flows.any { !it.complete }) {
                val pkt = harness.pollTunPacket(1000)
                if (pkt == null) {
                    if (++idle > 3) break
                    flows.filter { !it.complete }.forEach {
                        feedAck(it, it.serverIsn + 1 + it.received); it.lastAck = it.received
                    }
                    continue
                }
                idle = 0
                val (ip, tcp) = harness.parseTcp(pkt)
                val flow = byPort.getValue(tcp.destinationPort)
                assertThat(ip.destinationIP.toString()).isEqualTo(clientIp)  // no cross-flow leakage
                val len = tcpPayloadLength(pkt)
                if (len <= 0) continue

                assertThat(tcp.sequenceNumber and 0xFFFFFFFFL).isEqualTo(flow.expectedSeq)
                flow.expectedSeq = (flow.expectedSeq + len) and 0xFFFFFFFFL
                flow.received += len
                flow.peakInFlight = maxOf(flow.peakInFlight, flow.received - flow.lastAck)

                if (flow.received - flow.lastAck >= flow.window / 2 || flow.received == flow.total.toLong()) {
                    feedAck(flow, flow.serverIsn + 1 + flow.received)
                    flow.lastAck = flow.received
                }
            }

            flows.forEach { flow ->
                assertThat(flow.received).isEqualTo(flow.total.toLong())
                assertThat(flow.peakInFlight).isAtMost(flow.window.toLong())
                assertThat(harness.sessionByKey(tcpKey(flow.clientPort, flow.peerPort))).isNotNull()
            }
            assertThat(harness.flowDao.countNotSyncedFlows()).isEqualTo(flows.size.toLong())
        } finally {
            flows.forEach { it.close() }
        }
    }

    @Test
    fun `a client port is reused for a fresh connection after the previous one is torn down`() {
        val server1 = ServerSocket(0, 50, InetAddress.getByName(peerIp))
        val peerPort = server1.localPort
        val clientPort = 52000
        try {
            val accept1 = executor.submit<Socket> { server1.accept() }
            handshake(clientPort, peerPort, isn = 1000L)
            val socket1 = accept1.get(3, TimeUnit.SECONDS)
            assertThat(harness.sessionByKey(tcpKey(clientPort, peerPort))).isNotNull()

            // RST marks the session aborting; closing the upstream then lets the NIO read path
            // tear it down and drop it from the table.
            harness.feed(
                TestPackets.tcpPacket(
                    clientIp, clientPort, peerIp, peerPort,
                    seq = 1001L, ack = 0, flags = TestPackets.RST,
                )
            )
            socket1.close()
            harness.await { if (harness.sessionByKey(tcpKey(clientPort, peerPort)) == null) Unit else null }

            // A new SYN on the same client port opens a fresh, independent session.
            val accept2 = executor.submit<Socket> { server1.accept() }
            val isn2 = 9000L
            harness.feed(
                TestPackets.tcpPacket(
                    clientIp, clientPort, peerIp, peerPort,
                    seq = isn2, ack = 0, flags = TestPackets.SYN, mss = mss,
                )
            )
            val synAck = harness.awaitTunPacketMatching { val (_, t) = harness.parseTcp(it); t.isSYN && t.isACK }
            assertThat(harness.parseTcp(synAck).second.ackNumber).isEqualTo(isn2 + 1)
            accept2.get(3, TimeUnit.SECONDS)
            assertThat(harness.sessionByKey(tcpKey(clientPort, peerPort))).isNotNull()
        } finally {
            server1.close()
        }
    }

    // --- helpers -------------------------------------------------------------

    private inner class Flow(val clientPort: Int, val window: Int, val total: Int) {
        lateinit var server: ServerSocket
        var peerPort = 0
        lateinit var accepted: Socket
        private lateinit var acceptFuture: java.util.concurrent.Future<Socket>
        var serverIsn = 0L
        var expectedSeq = 0L
        var received = 0L
        var lastAck = 0L
        var peakInFlight = 0L
        val complete get() = received >= total

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

    private fun feedSyn(flow: Flow) = harness.feed(
        TestPackets.tcpPacket(
            clientIp, flow.clientPort, peerIp, flow.peerPort,
            seq = 1000L, ack = 0, flags = TestPackets.SYN, mss = mss, windowSize = flow.window,
        )
    )

    private fun feedAck(flow: Flow, ackNumber: Long) = harness.feed(
        TestPackets.tcpPacket(
            clientIp, flow.clientPort, peerIp, flow.peerPort,
            seq = 1001L, ack = ackNumber, flags = TestPackets.ACK, windowSize = flow.window,
        )
    )

    /** Full handshake on a single (port, peer); returns the engine's server ISN. */
    private fun handshake(clientPort: Int, peerPort: Int, isn: Long): Long {
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = isn, ack = 0, flags = TestPackets.SYN, mss = mss,
            )
        )
        val synAck = harness.awaitTunPacketMatching { val (_, t) = harness.parseTcp(it); t.isSYN && t.isACK }
        val serverIsn = harness.parseTcp(synAck).second.sequenceNumber
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = isn + 1, ack = serverIsn + 1, flags = TestPackets.ACK,
            )
        )
        return serverIsn
    }

    private fun tcpKey(clientPort: Int, peerPort: Int): String = Session.getSessionKey(
        SessionProtocol.TCP,
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
