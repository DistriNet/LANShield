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
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TcpWindowedDownloadTest {

    private lateinit var harness: ForwardingTestHarness
    private lateinit var server: ServerSocket
    private lateinit var accepted: Socket
    private val executor = Executors.newCachedThreadPool()
    private var peerPort = 0

    private val clientIp = "10.0.0.2"
    private val clientPort = 50000
    private val peerIp = "127.0.0.1"
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
    fun `an ACK that reopens the window resumes sending the next in-order segment`() {
        val serverIsn = handshake(window = 2000)
        accepted.getOutputStream().apply { write(ByteArray(8000)); flush() }

        // Initial burst is capped at one window; nothing more until the client ACKs.
        val burst = drain()
        val burstBytes = burst.sumOf { it.len.toLong() }
        assertThat(burstBytes).isAtMost(2000L)
        assertThat(burstBytes).isAtLeast(2000L - mss)
        val nextSeq = (serverIsn + 1 + burstBytes) and 0xFFFFFFFFL

        // Open the window by acking the burst. No new upstream data is written, so resumption
        // can only come from the handler-driven pump flushing already-staged bytes.
        clientAck(serverIsn + 1 + burstBytes, window = 2000)

        val resumed = drain()
        assertThat(resumed).isNotEmpty()
        assertThat(resumed.first().seq).isEqualTo(nextSeq)
    }

    @Test
    fun `backpressure bounds the engine's staging buffer instead of pulling the whole file into memory`() {
        // A slow client that never acks: the engine must stop reading upstream once its staging
        // buffer is full, rather than pulling the entire response into memory.
        val window = 4 * 1024
        val total = 4 * 1024 * 1024 // far larger than any bounded staging buffer
        val serverIsn = handshake(window)
        val key = Session.getSessionKey(
            SessionProtocol.TCP,
            IPAddress(TestPackets.ip(peerIp)), peerPort,
            IPAddress(TestPackets.ip(clientIp)), clientPort,
        )

        executor.submit {
            runCatching { accepted.getOutputStream().apply { write(ByteArray(total)); flush() } }
        }

        val session = harness.await { harness.sessionByKey(key) }
        // The engine fills its staging buffer toward the cap, then stops reading.
        harness.await { session.takeIf { it.receivingStreamSize() >= 100 * 1024 } }
        Thread.sleep(200) // let any in-flight read settle

        // Staging stays bounded (~STAGING_CAP + one read chunk), nowhere near the 4 MB written.
        assertThat(session.receivingStreamSize()).isAtMost(256 * 1024)
    }

    @Test
    fun `a windowed client receives the whole download in order with in-flight bounded by the window`() {
        val window = 32 * 1024
        val total = 1024 * 1024
        val serverIsn = handshake(window, mss)

        val writeFuture: Future<*> = executor.submit {
            accepted.getOutputStream().apply { write(ByteArray(total)); flush() }
        }

        var received = 0L
        var lastAck = 0L
        var peakInFlight = 0L
        var expectedSeq = (serverIsn + 1) and 0xFFFFFFFFL
        var guard = 0
        while (received < total && guard++ < 1000) {
            for (seg in drain()) {
                assertThat(seg.seq).isEqualTo(expectedSeq)          // strictly in order, no gaps
                expectedSeq = (expectedSeq + seg.len) and 0xFFFFFFFFL
                received += seg.len
            }
            peakInFlight = maxOf(peakInFlight, received - lastAck)
            clientAck(serverIsn + 1 + received, window)
            lastAck = received
        }

        assertThat(received).isEqualTo(total.toLong())
        assertThat(peakInFlight).isAtMost(window.toLong())
        writeFuture.get(3, TimeUnit.SECONDS)                        // upstream completes once drained
    }

    @Test
    fun `FIN is not sent ahead of unsent data and follows the last byte`() {
        val window = 1000
        val total = 3000
        val serverIsn = handshake(window)

        accepted.getOutputStream().apply { write(ByteArray(total)); flush() }
        accepted.close() // upstream EOF, while staged data still exceeds the window

        val segs = ArrayList<Seg>()
        segs += drain() // initial burst: one window, no FIN (data still staged)
        var received = segs.sumOf { it.len.toLong() }
        var guard = 0
        while (received < total && guard++ < 100) {
            clientAck(serverIsn + 1 + received, window)
            val batch = drain()
            segs += batch
            received += batch.sumOf { it.len.toLong() }
        }

        val finIdx = segs.indexOfFirst { it.fin }
        assertThat(finIdx).isAtLeast(0) // a FIN was eventually sent
        // All payload bytes were delivered before the FIN (FIN never overtook unsent data).
        assertThat(segs.take(finIdx).sumOf { it.len.toLong() }).isEqualTo(total.toLong())
        // The FIN's sequence sits right after the last data byte.
        assertThat(segs[finIdx].seq).isEqualTo((serverIsn + 1 + total) and 0xFFFFFFFFL)
    }

    // --- helpers -------------------------------------------------------------

    private data class Seg(val seq: Long, val len: Int, val fin: Boolean)

    private fun handshake(window: Int, mss: Int = this.mss): Long {
        val acceptedFuture = executor.submit<Socket> { server.accept() }
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = clientIsn, ack = 0, flags = TestPackets.SYN, mss = mss, windowSize = window,
            )
        )
        val synAck = harness.awaitTunPacketMatching {
            val (_, tcp) = harness.parseTcp(it); tcp.isSYN && tcp.isACK
        }
        val serverIsn = harness.parseTcp(synAck).second.sequenceNumber
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = clientIsn + 1, ack = serverIsn + 1, flags = TestPackets.ACK, windowSize = window,
            )
        )
        accepted = acceptedFuture.get(3, TimeUnit.SECONDS)
        return serverIsn
    }

    private fun clientAck(ackNumber: Long, window: Int) {
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = clientIsn + 1, ack = ackNumber, flags = TestPackets.ACK, windowSize = window,
            )
        )
    }

    /** Drain TUN packets until the queue is quiet, returning each as a parsed segment. */
    private fun drain(timeoutMs: Long = 1000): List<Seg> {
        val out = ArrayList<Seg>()
        while (true) {
            val pkt = harness.pollTunPacket(timeoutMs) ?: break
            val (_, tcp) = harness.parseTcp(pkt)
            out.add(Seg(tcp.sequenceNumber and 0xFFFFFFFFL, tcpPayloadLength(pkt), tcp.isFIN))
        }
        return out
    }

    private fun tcpPayloadLength(packet: ByteArray): Int {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val dataOffset = ((packet[ihl + 12].toInt() shr 4) and 0x0F) * 4
        return totalLength - ihl - dataOffset
    }
}
