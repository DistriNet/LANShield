package tech.httptoolkit.android.vpn

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.distrinet.lanshield.database.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.httptoolkit.android.vpn.socket.IProtectSocket
import tech.httptoolkit.android.vpn.socket.SocketNIODataService
import tech.httptoolkit.android.vpn.socket.SocketProtector
import tech.httptoolkit.android.vpn.transport.ip.IPPacketFactory
import tech.httptoolkit.android.vpn.transport.tcp.TCPPacketFactory
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SlowClientDownloadInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var writer: CapturingWriter
    private lateinit var nioService: SocketNIODataService
    private lateinit var nioThread: Thread
    private lateinit var sessionManager: SessionManager
    private lateinit var sessionHandler: SessionHandler
    private lateinit var tempTun: File

    private lateinit var server: ServerSocket
    private val executor = Executors.newSingleThreadExecutor()
    private var peerPort = 0

    private val clientIp = "10.0.0.2"
    private val clientPort = 50000
    private val peerIp = "127.0.0.1"
    private val clientIsn = 1000L
    private val window = 8 * 1024          // small receive window the "client" advertises
    private val responseSize = 256 * 1024  // server pushes far more than one window

    private class CapturingWriter(out: FileOutputStream) : ClientPacketWriter(out) {
        val queue = LinkedBlockingQueue<ByteArray>()
        override fun write(data: ByteArray) { queue.add(data) }
    }

    @Before
    fun setUp() {
        SocketProtector.getInstance().setProtector(object : IProtectSocket {
            override fun protect(socket: Socket): Boolean = true
            override fun protect(socket: DatagramSocket): Boolean = true
        })
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()

        tempTun = File.createTempFile("tun", ".bin").apply { deleteOnExit() }
        writer = CapturingWriter(FileOutputStream(tempTun))
        nioService = SocketNIODataService(writer, db)
        nioThread = Thread(nioService, "nio-instrumented").apply { isDaemon = true; start() }
        sessionManager = SessionManager(db)
        sessionHandler = SessionHandler(sessionManager, nioService, writer, db)

        server = ServerSocket(0, 50, InetAddress.getByName(peerIp))
        peerPort = server.localPort
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        runCatching { server.close() }
        runCatching { nioService.shutdown() }
        runCatching { nioThread.join(1000) }
        runCatching { db.close() }
        runCatching { tempTun.delete() }
    }

    @Test
    fun windowedClientReceivesTheWholeDownloadInOrder() {
        val accepted = executor.submit<Socket> { server.accept() }

        // Handshake: SYN -> SYN-ACK -> ACK, advertising the small window.
        feed(syn())
        val synAck = awaitMatching { val (_, t) = parseTcp(it); t.isSYN && t.isACK }
        val serverIsn = parseTcp(synAck).second.sequenceNumber
        feed(ack(serverIsn + 1, window))
        val serverSocket = accepted.get(5, TimeUnit.SECONDS)

        // Server streams a response many windows in size, on a background thread (the engine
        // backpressures the upstream, so a single blocking write drains gradually as we ack).
        executor.submit {
            runCatching { serverSocket.getOutputStream().apply { write(ByteArray(responseSize)); flush() } }
        }

        // Conformant windowed client: accept each in-order segment, verify it never exceeds the
        // advertised window, and ack the contiguous prefix to keep the window open.
        val firstByte = serverIsn + 1
        var received = 0L
        var expectedSeq = firstByte and 0xFFFFFFFFL
        while (received < responseSize) {
            val pkt = writer.queue.poll(5000, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("download stalled after $received / $responseSize bytes")
            val (_, tcp) = parseTcp(pkt)
            val len = tcpPayloadLength(pkt)
            if (len <= 0) continue
            assertEquals("segments must arrive strictly in order", expectedSeq, tcp.sequenceNumber and 0xFFFFFFFFL)
            assertTrue("segment ($len B) exceeded the advertised window ($window B)", len <= window)
            expectedSeq = (expectedSeq + len) and 0xFFFFFFFFL
            received += len
            feed(ack(firstByte + received, window)) // ack the contiguous prefix, reopening the window
        }

        assertEquals("the windowed client must receive the whole file", responseSize.toLong(), received)
    }

    // --- engine plumbing helpers ---------------------------------------------

    private fun feed(packet: ByteArray) {
        sessionHandler.handlePacket(ByteBuffer.wrap(packet), "test.app")
    }

    private fun parseTcp(packet: ByteArray) = ByteBuffer.wrap(packet).let { b ->
        val ip = IPPacketFactory.createIPHeader(b)
        val tcp = TCPPacketFactory.createTCPHeader(b)
        ip to tcp
    }

    private fun awaitMatching(timeoutMs: Long = 5000, predicate: (ByteArray) -> Boolean): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            val pkt = writer.queue.poll(remaining, TimeUnit.MILLISECONDS) ?: break
            if (predicate(pkt)) return pkt
        }
        throw AssertionError("No matching TUN packet within ${timeoutMs}ms")
    }

    private fun tcpPayloadLength(packet: ByteArray): Int {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val dataOffset = ((packet[ihl + 12].toInt() shr 4) and 0x0F) * 4
        return totalLength - ihl - dataOffset
    }

    // --- raw IPv4 TCP packet builders (minimal, mirrors unit-test TestPackets) ------

    private val syn0 = 0x02
    private val ack0 = 0x10

    private fun syn(): ByteArray = tcpPacket(clientIsn, 0, syn0, mss = 1460, win = window)
    private fun ack(ackNum: Long, win: Int): ByteArray =
        tcpPacket(clientIsn + 1, ackNum, ack0, mss = null, win = win)

    private fun tcpPacket(seq: Long, ack: Long, flags: Int, mss: Int?, win: Int): ByteArray {
        val optionBytes = if (mss != null) 4 else 0
        val tcpHeaderLen = 20 + optionBytes
        val total = 20 + tcpHeaderLen
        val buf = ByteArray(total)
        // IPv4 header
        buf[0] = 0x45
        putShort(buf, 2, total)
        buf[8] = 64                 // TTL
        buf[9] = 6                  // protocol TCP
        ipBytes(clientIp).copyInto(buf, 12)
        ipBytes(peerIp).copyInto(buf, 16)
        // TCP header
        val t = 20
        putShort(buf, t, clientPort)
        putShort(buf, t + 2, peerPort)
        putInt(buf, t + 4, seq)
        putInt(buf, t + 8, ack)
        buf[t + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
        buf[t + 13] = flags.toByte()
        putShort(buf, t + 14, win)
        if (mss != null) {
            buf[t + 20] = 0x02; buf[t + 21] = 0x04; putShort(buf, t + 22, mss)
        }
        return buf
    }

    private fun ipBytes(dotted: String) =
        ByteArray(4) { dotted.split(".")[it].toInt().toByte() }

    private fun putShort(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xFF).toByte(); b[off + 1] = (v and 0xFF).toByte()
    }

    private fun putInt(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v ushr 24) and 0xFF).toByte(); b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte(); b[off + 3] = (v and 0xFF).toByte()
    }
}
