package org.distrinet.lanshield.database.model

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TreeSet

/** Robolectric is required because [OpenPorts.toJSON] uses `org.json`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenPortsJsonTest {

    @Test
    fun `toJSON serialises ports and strips the uid suffix from the package name`() {
        val openPorts = OpenPorts.createInstance("com.example.app:1234", "Example").copy(
            tcpPorts = TreeSet(listOf(443, 22)),
            udpPorts = TreeSet(listOf(53)),
        )

        val json = openPorts.toJSON()

        assertThat(json.getString("package_name")).isEqualTo("com.example.app")
        assertThat(json.getString("package_label")).isEqualTo("Example")

        val tcp = json.getJSONArray("tcp_ports")
        assertThat((0 until tcp.length()).map { tcp.getInt(it) }).containsExactly(22, 443).inOrder()

        val udp = json.getJSONArray("udp_ports")
        assertThat(udp.getInt(0)).isEqualTo(53)
    }
}
