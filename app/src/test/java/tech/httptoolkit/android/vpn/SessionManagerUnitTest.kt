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
