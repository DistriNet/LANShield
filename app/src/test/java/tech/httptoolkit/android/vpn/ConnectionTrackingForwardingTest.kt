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
import tech.httptoolkit.android.vpn.transport.ip.IPHeader
import tech.httptoolkit.android.vpn.transport.tcp.TCPHeader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionTrackingForwardingTest {

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

    // --- UDP -----------------------------------------------------------------

    @Test
    fun `concurrent udp connections demultiplex replies back to the originating client`() {
        val peer = DatagramSocket(0, InetAddress.getByName(peerIp)).apply { soTimeout = 3000 }
        val peerPort = peer.localPort
        try {
            // Two client connections to the same peer, distinguished only by source port.
            harness.feed(TestPackets.udpPacket(clientIp, 40001, peerIp, peerPort, "one".toByteArray()))
            harness.feed(TestPackets.udpPacket(clientIp, 40002, peerIp, peerPort, "two".toByteArray()))

            // The peer sees two distinct source sockets; echo each payload straight back.
            repeat(2) {
                val rx = DatagramPacket(ByteArray(64), 64)
                peer.receive(rx)
                peer.send(DatagramPacket(rx.data, rx.length, rx.socketAddress))
            }

            // Collect both TUN replies, then assert each landed on the correct client port:
            // "one" must return to 40001 and "two" to 40002 (no cross-talk).
            val byClientPort = buildMap {
                repeat(2) {
                    val (_, udp, payload) = harness.parseUdp(harness.awaitTunPacket())
                    put(udp.destinationPort, String(payload))
                }
            }
            assertThat(byClientPort).containsExactly(40001, "one", 40002, "two")
        } finally {
            peer.close()
        }
    }

    @Test
    fun `repeated udp datagrams reuse one connection and accumulate egress`() {
        val peer = DatagramSocket(0, InetAddress.getByName(peerIp)).apply { soTimeout = 3000 }
        val peerPort = peer.localPort
        try {
            harness.feed(TestPackets.udpPacket(clientIp, 40003, peerIp, peerPort, "p1".toByteArray()))
            harness.feed(TestPackets.udpPacket(clientIp, 40003, peerIp, peerPort, "p2".toByteArray()))

            // Both datagrams reach the peer over the same upstream socket: the second reused
            // the connection rather than opening a new one.
            val first = DatagramPacket(ByteArray(64), 64).also { peer.receive(it) }
            val second = DatagramPacket(ByteArray(64), 64).also { peer.receive(it) }
            assertThat(
                setOf(String(first.data, 0, first.length), String(second.data, 0, second.length))
            ).containsExactly("p1", "p2")
            assertThat(second.socketAddress).isEqualTo(first.socketAddress)

            // Exactly one session/flow was created; egress counters accumulate across both.
            val session = harness.await {
                harness.sessionByKey(udpKey(40003, peerPort))
            }
            assertThat(harness.flowDao.countNotSyncedFlows()).isEqualTo(1)
            harness.await { session.flow.takeIf { it.packetCountEgress >= 2 } }
        } finally {
            peer.close()
        }
    }

    @Test
    fun `back-to-back udp datagrams preserve message boundaries`() {
        // Regression test for datagram coalescing: two datagrams sent on the same connection
        // before the writer drains must NOT be merged into one upstream datagram.
        val peer = DatagramSocket(0, InetAddress.getByName(peerIp)).apply { soTimeout = 3000 }
        val peerPort = peer.localPort
        try {
            harness.feed(TestPackets.udpPacket(clientIp, 40004, peerIp, peerPort, "AAAA".toByteArray()))
            harness.feed(TestPackets.udpPacket(clientIp, 40004, peerIp, peerPort, "BBBB".toByteArray()))

            // The peer must receive two distinct 4-byte datagrams, not one merged "AAAABBBB".
            val first = DatagramPacket(ByteArray(64), 64).also { peer.receive(it) }
            val second = DatagramPacket(ByteArray(64), 64).also { peer.receive(it) }
            assertThat(first.length).isEqualTo(4)
            assertThat(second.length).isEqualTo(4)
            assertThat(listOf(String(first.data, 0, 4), String(second.data, 0, 4)))
                .containsExactly("AAAA", "BBBB").inOrder()
        } finally {
            peer.close()
        }
    }

    // --- TCP -----------------------------------------------------------------

    @Test
    fun `concurrent tcp connections are tracked independently`() {
        val server = ServerSocket(0, 50, InetAddress.getByName(peerIp))
        val peerPort = server.localPort
        val executor = Executors.newFixedThreadPool(2)
        val isn1 = 1000L
        val isn2 = 5000L
        try {
            val accept1 = executor.submit<Socket> { server.accept() }
            val accept2 = executor.submit<Socket> { server.accept() }

            harness.feed(syn(40001, peerPort, isn1))
            harness.feed(syn(40002, peerPort, isn2))

            // Each SYN-ACK must be demultiplexed to its own client port and acknowledge that
            // connection's ISN — proving the two handshakes are not conflated.
            val synAcks = buildMap<Int, Pair<IPHeader, TCPHeader>> {
                repeat(2) {
                    val pkt = harness.awaitTunPacketMatching {
                        val (_, tcp) = harness.parseTcp(it); tcp.isSYN && tcp.isACK
                    }
                    val parsed = harness.parseTcp(pkt)
                    put(parsed.second.destinationPort, parsed)
                }
            }

            assertThat(synAcks.keys).containsExactly(40001, 40002)
            assertThat(synAcks.getValue(40001).second.ackNumber).isEqualTo(isn1 + 1)
            assertThat(synAcks.getValue(40002).second.ackNumber).isEqualTo(isn2 + 1)
            assertThat(synAcks.getValue(40001).first.destinationIP.toString()).isEqualTo(clientIp)
            assertThat(synAcks.getValue(40002).first.destinationIP.toString()).isEqualTo(clientIp)

            // Two distinct sessions and two recorded flows.
            assertThat(harness.sessionByKey(tcpKey(40001, peerPort))).isNotNull()
            assertThat(harness.sessionByKey(tcpKey(40002, peerPort))).isNotNull()
            assertThat(harness.flowDao.countNotSyncedFlows()).isEqualTo(2)

            accept1.get(3, TimeUnit.SECONDS)
            accept2.get(3, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            server.close()
        }
    }

    @Test
    fun `tcp data for an unknown connection is rejected with RST and creates no session`() {
        // An ACK with payload but no preceding SYN has no tracked connection: the engine must
        // reject it with a RST rather than silently adopting it or crashing.
        harness.feed(
            TestPackets.tcpPacket(
                clientIp, 40009, peerIp, 9,
                seq = 42, ack = 99, flags = TestPackets.ACK, payload = "junk".toByteArray(),
            )
        )

        val rst = harness.awaitTunPacketMatching { harness.parseTcp(it).second.isRST }
        val (ip, tcp) = harness.parseTcp(rst)
        assertThat(tcp.isRST).isTrue()
        assertThat(ip.destinationIP.toString()).isEqualTo(clientIp)
        assertThat(tcp.destinationPort).isEqualTo(40009)

        assertThat(harness.sessionByKey(tcpKey(40009, 9))).isNull()
        assertThat(harness.flowDao.countNotSyncedFlows()).isEqualTo(0)
    }

    // --- helpers -------------------------------------------------------------

    private fun syn(clientPort: Int, peerPort: Int, isn: Long): ByteArray =
        TestPackets.tcpPacket(
            clientIp, clientPort, peerIp, peerPort,
            seq = isn, ack = 0, flags = TestPackets.SYN, mss = 1460,
        )

    private fun udpKey(clientPort: Int, peerPort: Int): String = Session.getSessionKey(
        SessionProtocol.UDP,
        IPAddress(TestPackets.ip(peerIp)), peerPort,
        IPAddress(TestPackets.ip(clientIp)), clientPort,
    )

    private fun tcpKey(clientPort: Int, peerPort: Int): String = Session.getSessionKey(
        SessionProtocol.TCP,
        IPAddress(TestPackets.ip(peerIp)), peerPort,
        IPAddress(TestPackets.ip(clientIp)), clientPort,
    )
}
