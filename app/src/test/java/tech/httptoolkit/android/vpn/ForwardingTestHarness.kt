package tech.httptoolkit.android.vpn

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.distrinet.lanshield.database.AppDatabase
import org.distrinet.lanshield.database.dao.FlowDao
import tech.httptoolkit.android.vpn.socket.IProtectSocket
import tech.httptoolkit.android.vpn.socket.SocketNIODataService
import tech.httptoolkit.android.vpn.socket.SocketProtector
import tech.httptoolkit.android.vpn.transport.ip.IPHeader
import tech.httptoolkit.android.vpn.transport.ip.IPPacketFactory
import tech.httptoolkit.android.vpn.transport.tcp.TCPHeader
import tech.httptoolkit.android.vpn.transport.tcp.TCPPacketFactory
import tech.httptoolkit.android.vpn.transport.udp.UDPHeader
import tech.httptoolkit.android.vpn.transport.udp.UDPPacketFactory
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CapturingClientPacketWriter(out: FileOutputStream) : ClientPacketWriter(out) {
    val queue = LinkedBlockingQueue<ByteArray>()
    override fun write(data: ByteArray) {
        queue.add(data)
    }
}

class ForwardingTestHarness : AutoCloseable {

    val db: AppDatabase
    val flowDao: FlowDao
    val writer: CapturingClientPacketWriter
    val nioService: SocketNIODataService
    val sessionManager: SessionManager
    val sessionHandler: SessionHandler
    private val nioThread: Thread
    private val tempTunFile: File

    init {
        installNoOpProtector()
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        flowDao = db.FlowDao()

        tempTunFile = File.createTempFile("tun", ".bin").apply { deleteOnExit() }
        writer = CapturingClientPacketWriter(FileOutputStream(tempTunFile))

        nioService = SocketNIODataService(writer, db)
        nioThread = Thread(nioService, "nio-test").apply { isDaemon = true; start() }

        sessionManager = SessionManager(db)
        sessionHandler = SessionHandler(sessionManager, nioService, writer, db)
    }

    /** Feed a raw IP packet into the engine, exactly as VPNRunnable.run() would. */
    fun feed(packet: ByteArray, packageName: String = "test.app") {
        sessionHandler.handlePacket(ByteBuffer.wrap(packet), packageName)
    }

    /** Block until the engine emits a TUN-bound packet, or fail. */
    fun awaitTunPacket(timeoutMs: Long = 2000): ByteArray =
        writer.queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("No TUN packet captured within ${timeoutMs}ms")

    /** Like [awaitTunPacket] but returns null on timeout instead of failing. */
    fun pollTunPacket(timeoutMs: Long = 2000): ByteArray? =
        writer.queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun awaitTunPacketMatching(timeoutMs: Long = 2000, predicate: (ByteArray) -> Boolean): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            val packet = writer.queue.poll(remainingMs, TimeUnit.MILLISECONDS) ?: break
            if (predicate(packet)) return packet
        }
        throw AssertionError("No matching TUN packet captured within ${timeoutMs}ms")
    }

    fun parseTcp(packet: ByteArray): Pair<IPHeader, TCPHeader> {
        val b = ByteBuffer.wrap(packet)
        val ip = IPPacketFactory.createIPHeader(b)
        val tcp = TCPPacketFactory.createTCPHeader(b)
        return ip to tcp
    }

    fun parseUdp(packet: ByteArray): Triple<IPHeader, UDPHeader, ByteArray> {
        val b = ByteBuffer.wrap(packet)
        val ip = IPPacketFactory.createIPHeader(b)
        val udp = UDPPacketFactory.createUDPHeader(b)
        val payload = ByteArray(b.remaining())
        b.get(payload)
        return Triple(ip, udp, payload)
    }

    fun sessionByKey(key: String): Session? = sessionManager.getSessionByKey(key)

    /** Poll [block] until it returns non-null, or fail. */
    fun <T : Any> await(timeoutMs: Long = 2000, intervalMs: Long = 10, block: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            block()?.let { return it }
            Thread.sleep(intervalMs)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    override fun close() {
        runCatching { nioService.shutdown() }
        runCatching { nioThread.join(1000) }
        runCatching { db.close() }
        runCatching { tempTunFile.delete() }
    }

    companion object {
        /** Idempotent: SocketProtector is set-once per JVM, so re-calls are no-ops. */
        fun installNoOpProtector() {
            SocketProtector.getInstance().setProtector(object : IProtectSocket {
                override fun protect(socket: Socket): Boolean = true
                override fun protect(socket: DatagramSocket): Boolean = true
            })
        }
    }
}
