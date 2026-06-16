package tech.httptoolkit.android.vpn

import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

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
