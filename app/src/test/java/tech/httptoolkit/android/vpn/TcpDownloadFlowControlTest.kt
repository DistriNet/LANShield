package tech.httptoolkit.android.vpn

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifies TCP receive-window flow control on the download (server -> app) path: the engine
 * must never have more unacknowledged data in flight than the client's advertised window.
 *
 * Previously the engine ignored the window and pushed the whole response unacknowledged,
 * which is why large downloads failed in slow-draining clients (Firefox while Chrome/curl
 * succeeded). This drives a download where the client advertises a tiny window and sends NO
 * further ACKs, and asserts the engine stops after ~one window instead of over-sending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TcpDownloadFlowControlTest {

    private lateinit var harness: ForwardingTestHarness
    private lateinit var server: ServerSocket
    private val executor = Executors.newSingleThreadExecutor()
    private var peerPort = 0

    private val clientIp = "10.0.0.2"
    private val clientPort = 50000
    private val peerIp = "127.0.0.1"
    private val clientIsn = 1000L
    private val advertisedWindow = 4096
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
    fun `engine does not send more than the client receive window without ACKs`() {
        val acceptedFuture = executor.submit<Socket> { server.accept() }

        // Handshake, advertising a deliberately tiny receive window.
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = clientIsn, ack = 0, flags = TestPackets.SYN, mss = mss,
                windowSize = advertisedWindow,
            )
        )
        val synAck = harness.awaitTunPacketMatching {
            val (_, tcp) = harness.parseTcp(it); tcp.isSYN && tcp.isACK
        }
        val serverIsn = harness.parseTcp(synAck).second.sequenceNumber
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = clientIsn + 1, ack = serverIsn + 1, flags = TestPackets.ACK,
                windowSize = advertisedWindow,
            )
        )
        val accepted = acceptedFuture.get(3, TimeUnit.SECONDS)

        // The server streams a large response. The client (this test) deliberately sends NO
        // further ACKs and never opens its window beyond the 4 KB advertised above.
        val responseSize = 256 * 1024
        accepted.getOutputStream().apply { write(ByteArray(responseSize)); flush() }

        // Drain everything the engine pushes to the TUN until it goes quiet, summing payloads.
        var unackedBytes = 0L
        while (true) {
            val pkt = harness.pollTunPacket(1000) ?: break
            unackedBytes += tcpPayloadLength(pkt)
        }

        // With zero ACKs the engine may keep at most one advertised window in flight, and should
        // have filled that window (to within one segment) rather than stalling early.
        assertThat(unackedBytes).isAtMost(advertisedWindow.toLong())
        assertThat(unackedBytes).isAtLeast(advertisedWindow.toLong() - mss)
    }

    /** Payload length of a captured IPv4 TCP packet, from its header-length fields. */
    private fun tcpPayloadLength(packet: ByteArray): Int {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val dataOffset = ((packet[ihl + 12].toInt() shr 4) and 0x0F) * 4
        return totalLength - ihl - dataOffset
    }
}
