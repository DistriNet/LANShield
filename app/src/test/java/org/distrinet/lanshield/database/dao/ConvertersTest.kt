package org.distrinet.lanshield.database.dao

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.TreeSet

/** Pure-JVM tests for the Room [TypeConverters][androidx.room.TypeConverter]. */
class ConvertersTest {

    private val sortedInt = SortedIntSetConverter()
    private val stringList = StringListConverter()
    private val socket = InetSocketAddressConverter()

    @Test
    fun `sorted int set round-trips and stays sorted`() {
        assertThat(sortedInt.fromSortedIntSet(TreeSet(listOf(3, 1, 2)))).isEqualTo("1,2,3")
        assertThat(sortedInt.toSortedIntSet("3,1,2")).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `sorted int set handles null and blank`() {
        assertThat(sortedInt.fromSortedIntSet(null)).isEmpty()
        assertThat(sortedInt.toSortedIntSet(null)).isEmpty()
        assertThat(sortedInt.toSortedIntSet("")).isEmpty()
    }

    @Test
    fun `sorted int set skips non-numeric entries`() {
        assertThat(sortedInt.toSortedIntSet("1,foo,2")).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `string list round-trips and trims`() {
        assertThat(stringList.fromStringList(listOf("a", "b", "c"))).isEqualTo("a,b,c")
        assertThat(stringList.toStringList("a, b , c")).containsExactly("a", "b", "c").inOrder()
        assertThat(stringList.fromStringList(null)).isNull()
        assertThat(stringList.toStringList(null)).isNull()
    }

    @Test
    fun `inet socket address round-trips using ip literals`() {
        val address = InetSocketAddress(InetAddress.getByName("192.168.1.5"), 53)
        val encoded = socket.fromInetSocketAddress(address)
        assertThat(encoded).isEqualTo("192.168.1.5:53")

        val decoded = socket.toInetSocketAddress(encoded)!!
        assertThat(decoded.address.hostAddress).isEqualTo("192.168.1.5")
        assertThat(decoded.port).isEqualTo(53)
    }

    @Test
    fun `inet socket address handles null and malformed input`() {
        assertThat(socket.fromInetSocketAddress(null)).isNull()
        assertThat(socket.toInetSocketAddress(null)).isNull()
        assertThat(socket.toInetSocketAddress("no-colon")).isNull()
        assertThat(socket.toInetSocketAddress("192.168.1.5:notaport")).isNull()
    }
}
