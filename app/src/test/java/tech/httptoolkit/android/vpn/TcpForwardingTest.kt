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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TcpForwardingTest {

    private lateinit var harness: ForwardingTestHarness
    private lateinit var server: ServerSocket
    private val executor = Executors.newSingleThreadExecutor()
    private var peerPort = 0

    private val clientIp = "10.0.0.2"
    private val clientPort = 50000
    private val peerIp = "127.0.0.1"
    private val clientIsn = 1000L

    @Before
    fun setUp() {
        harness = ForwardingTestHarness()
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        peerPort = server.localPort
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        server.close()
        harness.close()
    }

    private fun sessionKey(): String = Session.getSessionKey(
        SessionProtocol.TCP,
        IPAddress(TestPackets.ip(peerIp)), peerPort,
        IPAddress(TestPackets.ip(clientIp)), clientPort,
    )

    @Test
    fun `tcp handshake, data forwarding and response relay`() {
        // The peer accepts on a background thread; the engine connects during SYN handling.
        val acceptedFuture = executor.submit<Socket> { server.accept() }

        // 1. SYN -> SYN-ACK
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, clientPort, peerIp, peerPort,
                seq = clientIsn, ack = 0, flags = TestPackets.SYN, mss = 1460,
            )
        )
        val synAck = harness.awaitTunPacketMatching { val (_, tcp) = harness.parseTcp(it); tcp.isSYN && tcp.isACK }
        val (synAckIp, synAckTcp) = harness.parseTcp(synAck)
        assertThat(synAckTcp.ackNumber).isEqualTo(clientIsn + 1)
        assertThat(synAckTcp.sourcePort).isEqualTo(peerPort)
        assertThat(synAckTcp.destinationPort).isEqualTo(clientPort)
        assertThat(synAckIp.sourceIP.toString()).isEqualTo(peerIp)
        assertThat(synAckIp.destinationIP.toString()).isEqualTo(clientIp)
        val serverIsn = synAckTcp.sequenceNumber

        // session + flow created
        val session = harness.await { harness.sessionByKey(sessionKey()) }
        val flowUuid = session.flow.uuid
        assertThat(session.flow.transportLayerProtocol).isEqualTo("TCP")
        assertThat(session.flow.appId).isEqualTo("test.app")

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

        // 4. Peer responds -> engine relays a data segment to the TUN (ingress)
        val response = "HTTP/1.1 200"
        accepted.getOutputStream().apply { write(response.toByteArray()); flush() }
        val dataPacket = harness.awaitTunPacketMatching { packet ->
            packet.size > 40 && TestPackets.payloadString(packet, response.length) == response
        }
        val (_, dataTcp) = harness.parseTcp(dataPacket)
        assertThat(dataTcp.isACK).isTrue()
        assertThat(dataTcp.sequenceNumber).isEqualTo(serverIsn + 1)
        assertThat(dataTcp.ackNumber).isEqualTo(clientIsn + 1 + request.length)

        // 5. Peer closes -> engine sends FIN to the TUN
        accepted.close()
        harness.awaitTunPacketMatching { val (_, tcp) = harness.parseTcp(it); tcp.isFIN }
    }

    private fun readFully(socket: Socket, buffer: ByteArray) {
        val input = socket.getInputStream()
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
    }
}
