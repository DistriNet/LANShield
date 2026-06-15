package tech.httptoolkit.android.vpn

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.distrinet.lanshield.database.model.LANFlow
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

/**
 * End-to-end UDP forwarding against a real loopback peer: feed a UDP packet into the
 * engine, assert the peer receives it (egress), the peer's reply is emitted back to the
 * TUN (ingress), and a LANFlow is recorded with updated counters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UdpForwardingTest {

    private lateinit var harness: ForwardingTestHarness
    private lateinit var peer: DatagramSocket
    private var peerPort = 0

    private val clientIp = "10.0.0.2"
    private val clientPort = 50000
    private val peerIp = "127.0.0.1"

    @Before
    fun setUp() {
        harness = ForwardingTestHarness()
        peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3000 }
        peerPort = peer.localPort
    }

    @After
    fun tearDown() {
        peer.close()
        harness.close()
    }

    private fun sessionKey(): String = Session.getSessionKey(
        SessionProtocol.UDP,
        IPAddress(TestPackets.ip(peerIp)), peerPort,
        IPAddress(TestPackets.ip(clientIp)), clientPort,
    )

    @Test
    fun `udp datagram is forwarded to the peer and the reply returns to the TUN`() {
        val packet = TestPackets.udpPacket(clientIp, clientPort, peerIp, peerPort, "ping".toByteArray())
        harness.feed(packet)

        // (a) egress: the real peer receives the exact payload
        val received = DatagramPacket(ByteArray(64), 64)
        peer.receive(received)
        assertThat(String(received.data, 0, received.length)).isEqualTo("ping")

        // (b) ingress: peer replies; engine emits a UDP packet to the TUN with swapped endpoints
        peer.send(DatagramPacket("pong".toByteArray(), 4, received.socketAddress))
        val (ip, udp, payload) = harness.parseUdp(harness.awaitTunPacket())
        assertThat(String(payload)).isEqualTo("pong")
        assertThat(ip.sourceIP.toString()).isEqualTo(peerIp)
        assertThat(ip.destinationIP.toString()).isEqualTo(clientIp)
        assertThat(udp.sourcePort).isEqualTo(peerPort)
        assertThat(udp.destinationPort).isEqualTo(clientPort)

        // (c) a flow was recorded; counters reflect egress + ingress (re-read from the DB)
        val session = harness.await { harness.sessionByKey(sessionKey()) }
        val flowUuid = session.flow.uuid
        assertThat(session.flow.appId).isEqualTo("test.app")
        assertThat(session.flow.transportLayerProtocol).isEqualTo("UDP")

        val persisted: LANFlow = harness.await {
            harness.flowDao.getFlowById(flowUuid)?.takeIf { it.dataIngress >= 4 }
        }
        assertThat(persisted.packetCountEgress).isAtLeast(1)
        assertThat(persisted.dataIngress).isAtLeast(4)
    }
}
