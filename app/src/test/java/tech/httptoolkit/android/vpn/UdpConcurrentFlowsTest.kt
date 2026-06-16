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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UdpConcurrentFlowsTest {

    private lateinit var harness: ForwardingTestHarness
    private val clientIp = "10.0.0.2"
    private val peerIp = "127.0.0.1"

    @Before
    fun setUp() {
        harness = ForwardingTestHarness()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun `many concurrent udp flows each get their reply back on the originating port`() {
        val peer = DatagramSocket(0, InetAddress.getByName(peerIp)).apply { soTimeout = 3000 }
        val peerPort = peer.localPort
        val ports = (41000 until 41008).toList()
        try {
            ports.forEach { port -> harness.feed(udp(port, peerPort, "msg-$port")) }

            // The peer sees one source socket per flow; echo each payload back.
            repeat(ports.size) {
                val rx = DatagramPacket(ByteArray(128), 128)
                peer.receive(rx)
                peer.send(DatagramPacket(rx.data, rx.length, rx.socketAddress))
            }

            // Each reply must return on the client port that originated it.
            val byPort = buildMap {
                repeat(ports.size) {
                    val (_, udp, payload) = harness.parseUdp(harness.awaitTunPacket())
                    put(udp.destinationPort, String(payload))
                }
            }
            assertThat(byPort).isEqualTo(ports.associateWith { "msg-$it" })

            ports.forEach { assertThat(harness.sessionByKey(udpKey(it, peerPort))).isNotNull() }
            assertThat(harness.flowDao.countNotSyncedFlows()).isEqualTo(ports.size.toLong())
        } finally {
            peer.close()
        }
    }

    @Test
    fun `the same source port to different destinations is tracked as separate connections`() {
        val peerA = DatagramSocket(0, InetAddress.getByName(peerIp)).apply { soTimeout = 3000 }
        val peerB = DatagramSocket(0, InetAddress.getByName(peerIp)).apply { soTimeout = 3000 }
        val clientPort = 41100
        try {
            harness.feed(udp(clientPort, peerA.localPort, "to-A"))
            harness.feed(udp(clientPort, peerB.localPort, "to-B"))

            echoOnce(peerA)
            echoOnce(peerB)

            // Both replies share the client port, so distinguish them by source (peer) port.
            val bySource = buildMap {
                repeat(2) {
                    val (_, udp, payload) = harness.parseUdp(harness.awaitTunPacket())
                    assertThat(udp.destinationPort).isEqualTo(clientPort)
                    put(udp.sourcePort, String(payload))
                }
            }
            assertThat(bySource).isEqualTo(mapOf(peerA.localPort to "to-A", peerB.localPort to "to-B"))

            // Two independent sessions, keyed by the full tuple.
            assertThat(harness.sessionByKey(udpKey(clientPort, peerA.localPort))).isNotNull()
            assertThat(harness.sessionByKey(udpKey(clientPort, peerB.localPort))).isNotNull()
            assertThat(harness.flowDao.countNotSyncedFlows()).isEqualTo(2)
        } finally {
            peerA.close()
            peerB.close()
        }
    }

    @Test
    fun `a burst of datagrams on one flow preserves boundaries and ordering`() {
        val peer = DatagramSocket(0, InetAddress.getByName(peerIp)).apply { soTimeout = 3000 }
        val peerPort = peer.localPort
        val clientPort = 41200
        // Varied sizes (incl. near-MTU), each tagged by index in its first byte.
        val sizes = listOf(1, 4, 16, 100, 500, 1400, 7, 64, 1200, 3)
        try {
            sizes.forEachIndexed { i, size ->
                harness.feed(udpBytes(clientPort, peerPort, ByteArray(size) { i.toByte() }))
            }

            // The peer must receive these datagrams: same sizes, same order, unmerged.
            sizes.forEachIndexed { i, size ->
                val rx = DatagramPacket(ByteArray(2048), 2048)
                peer.receive(rx)
                assertThat(rx.length).isEqualTo(size)
                assertThat(rx.data[0].toInt()).isEqualTo(i)  // datagram i not merged with i+1
            }

            val session = harness.await { harness.sessionByKey(udpKey(clientPort, peerPort)) }
            assertThat(harness.flowDao.countNotSyncedFlows()).isEqualTo(1)
            harness.await { session.flow.takeIf { it.packetCountEgress >= sizes.size } }
        } finally {
            peer.close()
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun echoOnce(peer: DatagramSocket) {
        val rx = DatagramPacket(ByteArray(128), 128)
        peer.receive(rx)
        peer.send(DatagramPacket(rx.data, rx.length, rx.socketAddress))
    }

    private fun udp(clientPort: Int, peerPort: Int, payload: String): ByteArray =
        udpBytes(clientPort, peerPort, payload.toByteArray())

    private fun udpBytes(clientPort: Int, peerPort: Int, payload: ByteArray): ByteArray =
        TestPackets.udpPacket(clientIp, clientPort, peerIp, peerPort, payload)

    private fun udpKey(clientPort: Int, peerPort: Int): String = Session.getSessionKey(
        SessionProtocol.UDP,
        IPAddress(TestPackets.ip(peerIp)), peerPort,
        IPAddress(TestPackets.ip(clientIp)), clientPort,
    )
}
