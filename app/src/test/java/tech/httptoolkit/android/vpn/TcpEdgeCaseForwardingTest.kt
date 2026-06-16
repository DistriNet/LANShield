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
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Adversarial / edge-case checks on the TCP path: 32-bit sequence wraparound in the ACK
 * accounting, a zero receive window that later reopens, RST teardown, FIN acknowledgement,
 * out-of-order (duplicate) client data, and a retransmitted SYN. These are the corners where
 * the sequence arithmetic and connection-tracking are most likely to misbehave.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TcpEdgeCaseForwardingTest {

    private lateinit var harness: ForwardingTestHarness
    private lateinit var server: ServerSocket
    private val executor = Executors.newCachedThreadPool()
    private var peerPort = 0

    private val clientIp = "10.0.0.2"
    private val peerIp = "127.0.0.1"
    private val clientPort = 53000
    private val clientIsn = 1000L
    private val mss = 1460

    @Before
    fun setUp() {
        harness = ForwardingTestHarness()
        server = ServerSocket(0, 50, InetAddress.getByName(peerIp))
        peerPort = server.localPort
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        server.close()
        harness.close()
    }

    @Test
    fun `acceptAck handles a sequence number that has wrapped past 2^32`() {
        val accept = executor.submit<Socket> { server.accept() }
        handshake(window = 65535)
        accept.get(3, TimeUnit.SECONDS)
        val session = harness.sessionByKey(tcpKey())!!

        // Seed the send accounting straddling the wrap (sendUnack at 2^32, sendNext just past).
        // The client acks the absolute sendNext, whose wire value 0x100 is below the prior wire
        // ack — the case the old signed compare mishandled.
        session.setSendUnack(0x1_0000_0000L)
        session.setSendNext(0x1_0000_0100L)

        feedAck(ackNumber = 0x0000_0100L, window = 65535)

        assertThat(session.getSendUnack()).isEqualTo(0x1_0000_0100L)
    }

    @Test
    fun `a zero window holds back data until an ACK reopens it`() {
        val accept = executor.submit<Socket> { server.accept() }
        val serverIsn = handshake(window = 0)
        val socket = accept.get(3, TimeUnit.SECONDS)

        // Server has data, but the client advertised a zero window: nothing may be sent.
        socket.getOutputStream().apply { write(ByteArray(8000)); flush() }
        assertThat(firstDataSegment(timeoutMs = 500)).isNull()

        // Opening the window releases the staged data, starting at the first unsent byte.
        feedAck(ackNumber = serverIsn + 1, window = 4096)
        val seg = harness.await { firstDataSegment(timeoutMs = 1000) }
        assertThat(seg.seq).isEqualTo((serverIsn + 1) and 0xFFFFFFFFL)
    }

    @Test
    fun `a client RST marks the connection aborting`() {
        val accept = executor.submit<Socket> { server.accept() }
        handshake(window = 65535)
        accept.get(3, TimeUnit.SECONDS)
        val session = harness.sessionByKey(tcpKey())!!

        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = clientIsn + 1, ack = 0, flags = TestPackets.RST,
            )
        )
        assertThat(session.isAbortingConnection()).isTrue()
    }

    @Test
    fun `a client FIN is acked, tears down the session, and frees the port for reuse`() {
        val accept = executor.submit<Socket> { server.accept() }
        val serverIsn = handshake(window = 65535)
        accept.get(3, TimeUnit.SECONDS)
        assertThat(harness.sessionByKey(tcpKey())).isNotNull()

        // Client closes with FIN+ACK; the engine acks it to the right client endpoint...
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = clientIsn + 1, ack = serverIsn + 1, flags = TestPackets.FIN or TestPackets.ACK,
            )
        )
        val finAck = harness.awaitTunPacketMatching { val (_, t) = harness.parseTcp(it); t.isFIN && t.isACK }
        val (ip, tcp) = harness.parseTcp(finAck)
        assertThat(tcp.destinationPort).isEqualTo(clientPort)
        assertThat(ip.destinationIP.toString()).isEqualTo(clientIp)

        // ...and fully removes the session, rather than re-adding it as a closed-channel zombie.
        assertThat(harness.sessionByKey(tcpKey())).isNull()

        // The same client port now establishes a fresh connection (the old entry is gone).
        val accept2 = executor.submit<Socket> { server.accept() }
        val isn2 = 7000L
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = isn2, ack = 0, flags = TestPackets.SYN, mss = mss,
            )
        )
        val synAck = harness.awaitTunPacketMatching { val (_, t) = harness.parseTcp(it); t.isSYN && t.isACK }
        assertThat(harness.parseTcp(synAck).second.ackNumber).isEqualTo(isn2 + 1)
        accept2.get(3, TimeUnit.SECONDS)
        assertThat(harness.sessionByKey(tcpKey())).isNotNull()
    }

    @Test
    fun `duplicate out-of-order client data is not re-delivered upstream`() {
        val accept = executor.submit<Socket> { server.accept() }
        handshake(window = 65535)
        val socket = accept.get(3, TimeUnit.SECONDS)
        val session = harness.sessionByKey(tcpKey())!!

        // In-order segment: pushed upstream, advancing recSequence.
        feedData(seq = clientIsn + 1, payload = "AAAA")
        val upstream = socket.getInputStream()
        val first = ByteArray(4).also { readFully(upstream, it) }
        assertThat(String(first)).isEqualTo("AAAA")
        val advancedRecSeq = session.getRecSequence()
        assertThat(advancedRecSeq).isEqualTo(clientIsn + 1 + 4)

        // The same segment again sits below recSequence: a duplicate, neither forwarded upstream
        // again nor allowed to move recSequence.
        feedData(seq = clientIsn + 1, payload = "AAAA")
        socket.soTimeout = 500
        assertThat(runCatching { upstream.read() }.exceptionOrNull())
            .isInstanceOf(SocketTimeoutException::class.java)
        assertThat(session.getRecSequence()).isEqualTo(advancedRecSeq)
    }

    @Test
    fun `a retransmitted SYN does not create a second session or flow`() {
        val accept = executor.submit<Socket> { server.accept() }
        val syn = TestPackets.tcpPacket(
            clientIp, clientPort, peerIp, peerPort,
            seq = clientIsn, ack = 0, flags = TestPackets.SYN, mss = mss,
        )

        harness.feed(syn)
        harness.awaitTunPacketMatching { val (_, t) = harness.parseTcp(it); t.isSYN && t.isACK }
        accept.get(3, TimeUnit.SECONDS)
        assertThat(harness.flowDao.countNotSyncedFlows()).isEqualTo(1)

        // Retransmitted SYN: the engine re-acks rather than opening a second session/flow.
        harness.feed(syn)
        val reply = harness.awaitTunPacket()
        assertThat(harness.parseTcp(reply).second.isSYN).isFalse()
        assertThat(harness.flowDao.countNotSyncedFlows()).isEqualTo(1)
        assertThat(harness.sessionByKey(tcpKey())).isNotNull()
    }

    // --- helpers -------------------------------------------------------------

    private data class Seg(val seq: Long, val len: Int)

    private fun handshake(window: Int): Long {
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = clientIsn, ack = 0, flags = TestPackets.SYN, mss = mss, windowSize = window,
            )
        )
        val synAck = harness.awaitTunPacketMatching { val (_, t) = harness.parseTcp(it); t.isSYN && t.isACK }
        val serverIsn = harness.parseTcp(synAck).second.sequenceNumber
        feedAck(ackNumber = serverIsn + 1, window = window)
        return serverIsn
    }

    private fun feedAck(ackNumber: Long, window: Int) = harness.feed(
        TestPackets.tcpPacket(
            clientIp, clientPort, peerIp, peerPort,
            seq = clientIsn + 1, ack = ackNumber, flags = TestPackets.ACK, windowSize = window,
        )
    )

    private fun feedData(seq: Long, payload: String) = harness.feed(
        TestPackets.tcpPacket(
            clientIp, clientPort, peerIp, peerPort,
            seq = seq, ack = 0, flags = TestPackets.ACK or TestPackets.PSH,
            payload = payload.toByteArray(), windowSize = 65535,
        )
    )

    /** Next captured segment carrying payload, or null if none arrives in time. */
    private fun firstDataSegment(timeoutMs: Long): Seg? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            val pkt = harness.pollTunPacket(remaining) ?: return null
            val len = tcpPayloadLength(pkt)
            if (len > 0) return Seg(harness.parseTcp(pkt).second.sequenceNumber and 0xFFFFFFFFL, len)
        }
        return null
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun tcpKey(): String = Session.getSessionKey(
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
