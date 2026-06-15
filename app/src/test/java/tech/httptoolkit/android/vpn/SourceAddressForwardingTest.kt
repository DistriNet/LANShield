package tech.httptoolkit.android.vpn

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.distrinet.lanshield.database.model.LANFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
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
 * The tun interface is assigned a synthetic VPN address (10.215.173.1), but because the
 * kernel picks a packet's source address from the destination, intercepted packets arrive
 * with two different source IPs:
 *
 *  - the VPN-tun IP (10.215.173.1) for destinations outside the device's own subnet, and
 *  - the device's real wlan IP (e.g. 192.168.1.100) for destinations on the local subnet.
 *
 * The forwarding engine handles the source IP opaquely, so both forms must round-trip
 * symmetrically: the peer's reply must return to the TUN addressed to whatever source the
 * client used, and the recorded [LANFlow.localEndpoint] must carry that same source. These
 * parameterized tests lock that in for both source-IP forms, for UDP and TCP.
 *
 * Only the client source IP is varied; the peer stays on loopback (127.0.0.1) since a JVM
 * test can't bind a peer on a real 192.168.x subnet and the engine connects to the
 * destination regardless of source.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SourceAddressForwardingTest(
    private val caseLabel: String,
    private val clientIp: String,
) {

    private lateinit var harness: ForwardingTestHarness

    private val clientPort = 50000
    private val peerIp = "127.0.0.1"

    @Before
    fun setUp() {
        harness = ForwardingTestHarness()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // --- UDP -----------------------------------------------------------------

    @Test
    fun `udp reply returns to the client source ip and the flow records it`() {
        val peer = DatagramSocket(0, InetAddress.getByName(peerIp)).apply { soTimeout = 3000 }
        val peerPort = peer.localPort
        try {
            harness.feed(
                TestPackets.udpPacket(clientIp, clientPort, peerIp, peerPort, "ping".toByteArray())
            )

            // egress: the real peer receives the exact payload
            val received = DatagramPacket(ByteArray(64), 64)
            peer.receive(received)
            assertThat(String(received.data, 0, received.length)).isEqualTo("ping")

            // ingress: the reply is emitted to the TUN addressed back to the client source IP
            peer.send(DatagramPacket("pong".toByteArray(), 4, received.socketAddress))
            val (ip, udp, payload) = harness.parseUdp(harness.awaitTunPacket())
            assertThat(String(payload)).isEqualTo("pong")
            assertThat(ip.sourceIP.toString()).isEqualTo(peerIp)
            assertThat(ip.destinationIP.toString()).isEqualTo(clientIp)
            assertThat(udp.sourcePort).isEqualTo(peerPort)
            assertThat(udp.destinationPort).isEqualTo(clientPort)

            // the recorded flow's local endpoint carries the client source IP
            val session = harness.await { harness.sessionByKey(sessionKey(SessionProtocol.UDP, peerPort)) }
            val flowUuid = session.flow.uuid
            assertThat(session.flow.transportLayerProtocol).isEqualTo("UDP")

            val persisted: LANFlow = harness.await {
                harness.flowDao.getFlowById(flowUuid)?.takeIf { it.dataIngress >= 4 }
            }
            assertThat(persisted.localEndpoint.address.hostAddress).isEqualTo(clientIp)
            assertThat(persisted.localEndpoint.port).isEqualTo(clientPort)
        } finally {
            peer.close()
        }
    }

    // --- TCP -----------------------------------------------------------------

    @Test
    fun `tcp reply returns to the client source ip and the flow records it`() {
        val server = ServerSocket(0, 50, InetAddress.getByName(peerIp))
        val peerPort = server.localPort
        val executor = Executors.newSingleThreadExecutor()
        val clientIsn = 1000L
        try {
            val acceptedFuture = executor.submit<Socket> { server.accept() }

            // 1. SYN -> SYN-ACK, addressed back to the client source IP
            harness.feed(
                TestPackets.tcpPacket(
                    clientIp, clientPort, peerIp, peerPort,
                    seq = clientIsn, ack = 0, flags = TestPackets.SYN, mss = 1460,
                )
            )
            val synAck = harness.awaitTunPacketMatching {
                val (_, tcp) = harness.parseTcp(it); tcp.isSYN && tcp.isACK
            }
            val (synAckIp, synAckTcp) = harness.parseTcp(synAck)
            assertThat(synAckTcp.ackNumber).isEqualTo(clientIsn + 1)
            assertThat(synAckIp.sourceIP.toString()).isEqualTo(peerIp)
            assertThat(synAckIp.destinationIP.toString()).isEqualTo(clientIp)
            val serverIsn = synAckTcp.sequenceNumber

            val session = harness.await { harness.sessionByKey(sessionKey(SessionProtocol.TCP, peerPort)) }
            val flowUuid = session.flow.uuid
            assertThat(session.flow.transportLayerProtocol).isEqualTo("TCP")

            // 2. ACK completes the handshake
            harness.feed(
                TestPackets.tcpPacket(
                    clientIp, clientPort, peerIp, peerPort,
                    seq = clientIsn + 1, ack = serverIsn + 1, flags = TestPackets.ACK,
                )
            )
            val accepted = acceptedFuture.get(3, TimeUnit.SECONDS)
            accepted.soTimeout = 3000
            harness.await { harness.flowDao.getFlowById(flowUuid)?.takeIf { it.tcpEstablishedReached } }

            // 3. PSH+ACK with payload -> peer receives it (egress)
            val request = "GET /"
            harness.feed(
                TestPackets.tcpPacket(
                    clientIp, clientPort, peerIp, peerPort,
                    seq = clientIsn + 1, ack = serverIsn + 1,
                    flags = TestPackets.PSH or TestPackets.ACK, payload = request.toByteArray(),
                )
            )
            val fromClient = ByteArray(request.length)
            readFully(accepted, fromClient)
            assertThat(String(fromClient)).isEqualTo(request)

            // 4. Peer responds -> the data segment is relayed to the TUN, addressed to the client
            val response = "HTTP/1.1 200"
            accepted.getOutputStream().apply { write(response.toByteArray()); flush() }
            val dataPacket = harness.awaitTunPacketMatching { packet ->
                packet.size > 40 && TestPackets.payloadString(packet, response.length) == response
            }
            val (dataIp, _) = harness.parseTcp(dataPacket)
            assertThat(dataIp.sourceIP.toString()).isEqualTo(peerIp)
            assertThat(dataIp.destinationIP.toString()).isEqualTo(clientIp)

            // the recorded flow's local endpoint carries the client source IP
            val persisted: LANFlow = harness.await { harness.flowDao.getFlowById(flowUuid) }
            assertThat(persisted.localEndpoint.address.hostAddress).isEqualTo(clientIp)
            assertThat(persisted.localEndpoint.port).isEqualTo(clientPort)

            accepted.close()
        } finally {
            executor.shutdownNow()
            server.close()
        }
    }

    private fun sessionKey(protocol: SessionProtocol, peerPort: Int): String =
        Session.getSessionKey(
            protocol,
            IPAddress(TestPackets.ip(peerIp)), peerPort,
            IPAddress(TestPackets.ip(clientIp)), clientPort,
        )

    private fun readFully(socket: Socket, buffer: ByteArray) {
        val input = socket.getInputStream()
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): Collection<Array<Any>> = listOf(
            // VPN-tun source: destination outside the device's own subnet.
            arrayOf("vpn-tun-source", "10.215.173.1"),
            // wlan source: destination on the device's own local subnet.
            arrayOf("wlan-source", "192.168.1.100"),
        )
    }
}
