package org.distrinet.lanshield.database.model

import android.app.Application
import android.util.JsonWriter
import com.google.common.truth.Truth.assertThat
import org.distrinet.lanshield.Policy
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringWriter
import java.net.InetAddress
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LANFlowJsonTest {

    private val remote = InetSocketAddress(InetAddress.getByName("8.8.8.8"), 443)
    private val local = InetSocketAddress(InetAddress.getByName("192.168.1.2"), 40000)

    private fun flow(protocol: String) =
        LANFlow.createFlow("com.example.app", remote, local, protocol, Policy.ALLOW)

    @Test
    fun `toJSON emits core fields and defaults a null dpi report`() {
        val json = flow("TCP").toJSON()

        assertThat(json.getString("app_id")).isEqualTo("com.example.app")
        assertThat(json.getString("transport_layer_protocol")).isEqualTo("TCP")
        // remote_ip is the InetSocketAddress string form (ip:port) with the leading "/" stripped
        assertThat(json.getString("remote_ip")).contains("8.8.8.8")
        assertThat(json.getInt("remote_port")).isEqualTo(443)
        assertThat(json.getString("dpi_report")).isEqualTo("{}")
        // TCP flows carry the established flag
        assertThat(json.has("tcp_established_reached")).isTrue()
    }

    @Test
    fun `toJSON omits tcp_established_reached for non-TCP flows`() {
        val json = flow("UDP").toJSON()
        assertThat(json.has("tcp_established_reached")).isFalse()
    }

    @Test
    fun `writeJson streams the same field set as toJSON`() {
        val out = StringWriter()
        JsonWriter(out).use { flow("TCP").writeJson(it) }

        val json = JSONObject(out.toString())
        assertThat(json.getString("app_id")).isEqualTo("com.example.app")
        assertThat(json.getString("transport_layer_protocol")).isEqualTo("TCP")
        assertThat(json.getBoolean("tcp_established_reached")).isFalse()
    }
}
