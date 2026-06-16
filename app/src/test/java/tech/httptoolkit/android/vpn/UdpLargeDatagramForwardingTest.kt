package tech.httptoolkit.android.vpn

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.httptoolkit.android.vpn.socket.DataConst
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Finding 1 (fixed): a UDP peer reply larger than the TUN MTU cannot be delivered as a single packet
 * and used to crash the NIO thread (ClientPacketWriter threw an Error on >30000-byte packets). The fix
 * drops oversized UDP responses in [SocketChannelReader.readUDP] (reporting once to Crashlytics) and
 * never crashes the engine.
 *
 * This test feeds a flow, has the peer send a >MTU reply (must be dropped — never reaches the TUN) and
 * then a small reply (must still be forwarded), proving the engine dropped the big one and stayed alive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UdpLargeDatagramForwardingTest {

    private lateinit var harness: ForwardingTestHarness
    private lateinit var peer: DatagramSocket
    private var peerPort = 0

    private val clientIp = "10.0.0.2"
    private val clientPort = 50001
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

    @Test
    fun `oversized udp reply is dropped and the engine stays alive`() {
        // Kick off the flow so the engine has a UDP session and the peer learns the return address.
        harness.feed(TestPackets.udpPacket(clientIp, clientPort, peerIp, peerPort, "hi".toByteArray()))

        val received = DatagramPacket(ByteArray(64), 64)
        peer.receive(received)

        // (a) Peer replies with a > MTU datagram: it must be dropped, never forwarded to the TUN.
        val bigPayload = ByteArray(40000) { 'x'.code.toByte() }
        peer.send(DatagramPacket(bigPayload, bigPayload.size, received.socketAddress))

        // (b) Peer then replies with a small datagram: the engine must still be alive and forward it.
        peer.send(DatagramPacket("ok".toByteArray(), 2, received.socketAddress))

        val small = harness.awaitTunPacketMatching { packet ->
            val (_, udp, payload) = harness.parseUdp(packet)
            udp.destinationPort == clientPort && String(payload) == "ok"
        }
        // The delivered packet is the small reply, and it is well under the MTU (the big one was dropped).
        assertThat(small.size).isLessThan(DataConst.MTU)

        // Nothing oversized was emitted to the TUN.
        var leftover = harness.pollTunPacket(200)
        while (leftover != null) {
            assertThat(leftover.size).isAtMost(DataConst.MTU)
            leftover = harness.pollTunPacket(200)
        }
    }
}
