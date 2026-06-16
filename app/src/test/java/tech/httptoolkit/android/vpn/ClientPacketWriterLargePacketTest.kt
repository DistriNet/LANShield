package tech.httptoolkit.android.vpn

import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * EXPOSES BUG (Finding 1): [ClientPacketWriter.write] throws a raw [Error] for any packet larger
 * than 30000 bytes (ClientPacketWriter.java:53). Because the writer is driven from the NIO thread
 * — which catches only IOException/channel exceptions — a single oversized packet (e.g. a UDP reply
 * up to MAX_RECEIVE_BUFFER_SIZE = 65535 bytes, see DataConst.java) propagates an uncaught Error that
 * kills the forwarding engine.
 *
 * This is a pure-JVM reproducer with no threads: write() only enqueues, so the size guard fires (or
 * doesn't) synchronously on the calling thread.
 *
 * Expected once fixed: oversized packets are handled gracefully (dropped/split/logged), never throw.
 * Until then `writes a 30001-byte packet without crashing` FAILS.
 */
class ClientPacketWriterLargePacketTest {

    private lateinit var tempFile: File
    private lateinit var writer: ClientPacketWriter

    @Before
    fun setUp() {
        tempFile = File.createTempFile("tun", ".bin").apply { deleteOnExit() }
        writer = ClientPacketWriter(FileOutputStream(tempFile))
    }

    @After
    fun tearDown() {
        writer.shutdown()
        tempFile.delete()
    }

    @Test
    fun `writes a 30000-byte packet without crashing`() {
        // Control: the boundary value is accepted today (guard is strictly > 30000).
        writer.write(ByteArray(30000))
    }

    @Test
    fun `writes a 30001-byte packet without crashing`() {
        // EXPOSES BUG: currently throws java.lang.Error("Packet too large"). A large UDP reply must
        // not be able to crash the writer / NIO thread.
        try {
            writer.write(ByteArray(30001))
        } catch (e: Throwable) {
            fail(
                "write() threw $e for a 30001-byte packet; oversized packets should be handled " +
                    "gracefully, not crash the writer (and thereby the NIO forwarding thread)."
            )
        }
    }
}
