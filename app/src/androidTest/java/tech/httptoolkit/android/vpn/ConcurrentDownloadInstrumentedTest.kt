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
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConcurrentDownloadInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var writer: CapturingWriter
    private lateinit var nioService: SocketNIODataService
    private lateinit var nioThread: Thread
    private lateinit var sessionManager: SessionManager
    private lateinit var sessionHandler: SessionHandler
    private lateinit var tempTun: File

    private val executor = Executors.newCachedThreadPool()

    private val clientIp = "10.0.0.2"
    private val peerIp = "127.0.0.1"
    private val mss = 1460

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
        nioThread = Thread(nioService, "nio-concurrent").apply { isDaemon = true; start() }
        sessionManager = SessionManager(db)
        sessionHandler = SessionHandler(sessionManager, nioService, writer, db)
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        runCatching { nioService.shutdown() }
        runCatching { nioThread.join(1000) }
        runCatching { db.close() }
        runCatching { tempTun.delete() }
    }

    @Test
    fun concurrentWindowedDownloadsAreEachDeliveredInOrderWithinTheirWindow() {
        val total = 128 * 1024
        val flows = listOf(
            Flow(clientPort = 50001, window = 8 * 1024, total = total),
            Flow(clientPort = 50002, window = 16 * 1024, total = total),
            Flow(clientPort = 50003, window = 32 * 1024, total = total),
        )
        val byPort = flows.associateBy { it.clientPort }

        try {
            flows.forEach { it.open() }

            // Handshake all flows; demultiplex SYN-ACKs back to each by client port.
            flows.forEach { feed(syn(it)) }
            repeat(flows.size) {
                val pkt = awaitMatching { val (_, t) = parseTcp(it); t.isSYN && t.isACK }
                val (_, tcp) = parseTcp(pkt)
                val flow = byPort.getValue(tcp.destinationPort)
                flow.serverIsn = tcp.sequenceNumber
                flow.expectedSeq = (tcp.sequenceNumber + 1) and 0xFFFFFFFFL
            }
            flows.forEach { feed(ack(it, it.serverIsn + 1)) }
            flows.forEach { it.accept() }

            flows.forEach { flow ->
                executor.submit {
                    runCatching { flow.accepted.getOutputStream().apply { write(ByteArray(total)); flush() } }
                }
            }

            // Drain loop, demuxing by client port and acking each flow at half its window.
            var idle = 0
            while (flows.any { !it.complete }) {
                val pkt = writer.queue.poll(2000, TimeUnit.MILLISECONDS)
                if (pkt == null) {
                    if (++idle > 3) break
                    flows.filter { !it.complete }.forEach { feed(ack(it, it.serverIsn + 1 + it.received)); it.lastAck = it.received }
                    continue
                }
                idle = 0
                val (ip, tcp) = parseTcp(pkt)
                val flow = byPort.getValue(tcp.destinationPort)
                assertEquals("reply addressed to wrong client", clientIp, ip.destinationIP.toString())
                val len = tcpPayloadLength(pkt)
                if (len <= 0) continue

                assertEquals("segments must arrive in order", flow.expectedSeq, tcp.sequenceNumber and 0xFFFFFFFFL)
                flow.expectedSeq = (flow.expectedSeq + len) and 0xFFFFFFFFL
                flow.received += len
                flow.peakInFlight = maxOf(flow.peakInFlight, flow.received - flow.lastAck)
                assertTrue("in-flight ${flow.received - flow.lastAck} exceeded window ${flow.window}",
                    flow.received - flow.lastAck <= flow.window)

                if (flow.received - flow.lastAck >= flow.window / 2 || flow.received == total.toLong()) {
                    feed(ack(flow, flow.serverIsn + 1 + flow.received)); flow.lastAck = flow.received
                }
            }

            flows.forEach { flow ->
                assertEquals("flow on ${flow.clientPort} must receive the whole file",
                    total.toLong(), flow.received)
                assertTrue("peak in-flight ${flow.peakInFlight} exceeded window ${flow.window}",
                    flow.peakInFlight <= flow.window)
            }
        } finally {
            flows.forEach { it.close() }
        }
    }

    // --- engine plumbing -----------------------------------------------------

    private fun feed(packet: ByteArray) = sessionHandler.handlePacket(ByteBuffer.wrap(packet), "test.app")

    private fun parseTcp(packet: ByteArray) = ByteBuffer.wrap(packet).let { b ->
        IPPacketFactory.createIPHeader(b) to TCPPacketFactory.createTCPHeader(b)
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

    // --- per-flow state ------------------------------------------------------

    private inner class Flow(val clientPort: Int, val window: Int, val total: Int) {
        lateinit var server: ServerSocket
        var peerPort = 0
        lateinit var accepted: Socket
        private lateinit var acceptFuture: Future<Socket>
        var serverIsn = 0L
        var expectedSeq = 0L
        var received = 0L
        var lastAck = 0L
        var peakInFlight = 0L
        val complete get() = received >= total

        fun open() {
            server = ServerSocket(0, 50, InetAddress.getByName(peerIp))
            peerPort = server.localPort
            acceptFuture = executor.submit<Socket> { server.accept() }
        }

        fun accept() { accepted = acceptFuture.get(5, TimeUnit.SECONDS) }

        fun close() {
            runCatching { if (this::accepted.isInitialized) accepted.close() }
            runCatching { server.close() }
        }
    }

    private fun syn(flow: Flow): ByteArray = tcpPacket(
        flow.clientPort, flow.peerPort, seq = 1000L, ack = 0, flags = SYN, mss = mss, win = flow.window,
    )

    private fun ack(flow: Flow, ackNum: Long): ByteArray = tcpPacket(
        flow.clientPort, flow.peerPort, seq = 1001L, ack = ackNum, flags = ACK, mss = null, win = flow.window,
    )

    // --- raw IPv4 TCP packet builder -----------------------------------------

    private val SYN = 0x02
    private val ACK = 0x10

    private fun tcpPacket(srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int, mss: Int?, win: Int): ByteArray {
        val optionBytes = if (mss != null) 4 else 0
        val tcpHeaderLen = 20 + optionBytes
        val total = 20 + tcpHeaderLen
        val buf = ByteArray(total)
        buf[0] = 0x45
        putShort(buf, 2, total)
        buf[8] = 64
        buf[9] = 6                  // protocol TCP
        ipBytes(clientIp).copyInto(buf, 12)
        ipBytes(peerIp).copyInto(buf, 16)
        val t = 20
        putShort(buf, t, srcPort)
        putShort(buf, t + 2, dstPort)
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

    private fun ipBytes(dotted: String) = ByteArray(4) { dotted.split(".")[it].toInt().toByte() }

    private fun putShort(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xFF).toByte(); b[off + 1] = (v and 0xFF).toByte()
    }

    private fun putInt(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v ushr 24) and 0xFF).toByte(); b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte(); b[off + 3] = (v and 0xFF).toByte()
    }
}
