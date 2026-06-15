package tech.httptoolkit.android.vpn

import android.app.Application
import android.util.SparseArray
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.distrinet.lanshield.database.AppDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.httptoolkit.android.vpn.transport.ip.IPAddress
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Tests [SessionManager] session keying, lifecycle and TCP port redirection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionManagerUnitTest {

    private lateinit var db: AppDatabase
    private lateinit var manager: SessionManager

    private val srcIp = IPAddress(byteArrayOf(10, 0, 0, 2))
    private val dstIp = IPAddress(TestPackets.ip("127.0.0.1"))

    @Before
    fun setUp() {
        ForwardingTestHarness.installNoOpProtector()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        manager = SessionManager(db)
    }

    @After
    fun tearDown() = db.close()

    private fun rawUdp(): ByteBuffer =
        ByteBuffer.wrap(TestPackets.udpPacket("10.0.0.2", 50000, "127.0.0.1", 9999, "x".toByteArray()))

    private fun createUdp(srcPort: Int = 50000, dstPort: Int = 9999) =
        manager.createNewUDPSession(dstIp, dstPort, srcIp, srcPort, 28, "pkg", rawUdp())

    @Test
    fun `created session is retrievable by tuple and by key`() {
        val session = createUdp()
        val key = Session.getSessionKey(SessionProtocol.UDP, dstIp, 9999, srcIp, 50000)

        assertThat(session.sessionKey).isEqualTo(key)
        assertThat(manager.getSessionByKey(key)).isSameInstanceAs(session)
        assertThat(manager.getSession(SessionProtocol.UDP, dstIp, 9999, srcIp, 50000))
            .isSameInstanceAs(session)
    }

    @Test
    fun `recreating the same udp session is deduplicated and inserts only one flow`() {
        val first = createUdp()
        val second = createUdp()
        assertThat(second).isSameInstanceAs(first)
        assertThat(db.FlowDao().countNotSyncedFlows()).isEqualTo(1)
    }

    @Test
    fun `recreating the same tcp session is deduplicated and inserts only one flow`() {
        // Two SYNs for the same 5-tuple (e.g. a retransmitted SYN) must map to one connection.
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        try {
            val port = server.localPort
            val first = manager.createNewTCPSession(dstIp, port, srcIp, 50000, 40, "pkg")
            val second = manager.createNewTCPSession(dstIp, port, srcIp, 50000, 40, "pkg")

            assertThat(second).isSameInstanceAs(first)
            assertThat(db.FlowDao().countNotSyncedFlows()).isEqualTo(1)
        } finally {
            server.close()
        }
    }

    @Test
    fun `udp and tcp with the same tuple are tracked as separate connections`() {
        // Protocol is part of the connection identity: identical addresses/ports over UDP and
        // TCP must not collide into one session.
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        try {
            val port = server.localPort
            val udp = manager.createNewUDPSession(
                dstIp, port, srcIp, 50000, 28, "pkg",
                ByteBuffer.wrap(TestPackets.udpPacket("10.0.0.2", 50000, "127.0.0.1", port, "x".toByteArray())),
            )
            val tcp = manager.createNewTCPSession(dstIp, port, srcIp, 50000, 40, "pkg")

            assertThat(tcp).isNotSameInstanceAs(udp)
            assertThat(tcp.sessionKey).isNotEqualTo(udp.sessionKey)
            assertThat(manager.getSession(SessionProtocol.UDP, dstIp, port, srcIp, 50000))
                .isSameInstanceAs(udp)
            assertThat(manager.getSession(SessionProtocol.TCP, dstIp, port, srcIp, 50000))
                .isSameInstanceAs(tcp)
            assertThat(db.FlowDao().countNotSyncedFlows()).isEqualTo(2)
        } finally {
            server.close()
        }
    }

    @Test
    fun `sessions are keyed by the full 5-tuple`() {
        // Same client to the same peer but a different source port, or to a different peer port,
        // are distinct connections, each independently retrievable and recorded.
        val base = createUdp(srcPort = 50000, dstPort = 9999)
        val differentSrcPort = createUdp(srcPort = 50001, dstPort = 9999)
        val differentDstPort = createUdp(srcPort = 50000, dstPort = 8888)

        assertThat(differentSrcPort).isNotSameInstanceAs(base)
        assertThat(differentDstPort).isNotSameInstanceAs(base)
        assertThat(db.FlowDao().countNotSyncedFlows()).isEqualTo(3)

        assertThat(manager.getSession(SessionProtocol.UDP, dstIp, 9999, srcIp, 50000))
            .isSameInstanceAs(base)
        assertThat(manager.getSession(SessionProtocol.UDP, dstIp, 9999, srcIp, 50001))
            .isSameInstanceAs(differentSrcPort)
        assertThat(manager.getSession(SessionProtocol.UDP, dstIp, 8888, srcIp, 50000))
            .isSameInstanceAs(differentDstPort)
    }

    @Test
    fun `closeSession removes the session and closes its channel`() {
        val session = createUdp()
        val key = session.sessionKey

        manager.closeSession(session)

        assertThat(manager.getSessionByKey(key)).isNull()
        assertThat(session.channel.isOpen).isFalse()
    }

    @Test
    fun `keepSessionAlive re-registers a session under its key`() {
        val session = createUdp()
        manager.closeSession(session)
        assertThat(manager.getSessionByKey(session.sessionKey)).isNull()

        manager.keepSessionAlive(session)
        assertThat(manager.getSessionByKey(session.sessionKey)).isSameInstanceAs(session)
    }

    @Test
    fun `tcp port redirection connects to the redirected address`() {
        val redirectServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val accepted = executor.submit<Socket> { redirectServer.accept() }

            val originalPort = 80
            val redirections = SparseArray<InetSocketAddress>().apply {
                put(originalPort, InetSocketAddress("127.0.0.1", redirectServer.localPort))
            }
            manager.setTcpPortRedirections(redirections)

            // Destination 10.9.9.9 is unreachable, but the redirection sends us to loopback.
            manager.createNewTCPSession(
                IPAddress(byteArrayOf(10, 9, 9, 9)), originalPort, srcIp, 50000, 40, "pkg",
            )

            assertThat(accepted.get(3, TimeUnit.SECONDS)).isNotNull()
        } finally {
            executor.shutdownNow()
            redirectServer.close()
        }
    }
}
