package tech.httptoolkit.android.vpn.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-JVM tests for the big-endian encode/decode and checksum helpers. */
class PacketUtilTest {

    @Test
    fun `writeIntToBytes and getNetworkInt round-trip`() {
        val buffer = ByteArray(4)
        PacketUtil.writeIntToBytes(0x01020304, buffer, 0)
        assertThat(buffer.map { it.toInt() and 0xFF })
            .containsExactly(0x01, 0x02, 0x03, 0x04).inOrder()
        assertThat(PacketUtil.getNetworkInt(buffer, 0, 4)).isEqualTo(0x01020304)
    }

    @Test
    fun `writeShortToBytes and getNetworkShort round-trip`() {
        val buffer = ByteArray(2)
        PacketUtil.writeShortToBytes(0x0A0B.toShort(), buffer, 0)
        assertThat(PacketUtil.getNetworkShort(buffer, 0)).isEqualTo(0x0A0B.toShort())
    }

    @Test
    fun `getNetworkInt caps the read length at four bytes`() {
        val buffer = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        assertThat(PacketUtil.getNetworkInt(buffer, 0, 8)).isEqualTo(0x01020304)
    }

    @Test
    fun `getNetworkLong reads a two-byte value`() {
        val buffer = byteArrayOf(0x00, 0x00, 0x12, 0x34)
        assertThat(PacketUtil.getNetworkLong(buffer, 2, 2)).isEqualTo(0x1234L)
    }

    @Test
    fun `calculateChecksum of zeros is all ones`() {
        val data = ByteArray(4)
        val checksum = PacketUtil.calculateChecksum(data, 0, 4)
        assertThat(checksum.map { it.toInt() and 0xFF }).containsExactly(0xFF, 0xFF).inOrder()
    }

    @Test
    fun `calculateChecksum is the ones complement of the sum`() {
        // sum of 0x0001 + 0x0002 = 3 -> ~3 = 0xFFFC
        val data = byteArrayOf(0x00, 0x01, 0x00, 0x02)
        val checksum = PacketUtil.calculateChecksum(data, 0, 4)
        assertThat(checksum.map { it.toInt() and 0xFF }).containsExactly(0xFF, 0xFC).inOrder()
    }
}
