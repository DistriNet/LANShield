package org.distrinet.lanshield.database.model

import com.google.common.truth.Truth.assertThat
import org.distrinet.lanshield.Policy
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure-JVM tests for the side-effect-free parts of [LANFlow]. JSON serialization
 * (which relies on `org.json` / `android.util.JsonWriter`) is covered separately
 * under Robolectric in [LANFlowJsonTest].
 */
class LANFlowTest {

    private val remote = InetSocketAddress(InetAddress.getByName("8.8.8.8"), 443)
    private val local = InetSocketAddress(InetAddress.getByName("192.168.1.2"), 40000)

    @Test
    fun `createFlow initialises a fresh zeroed flow`() {
        val flow = LANFlow.createFlow(
            appId = "com.example.app",
            remoteEndpoint = remote,
            localEndpoint = local,
            transportLayerProtocol = "TCP",
            appliedPolicy = Policy.ALLOW,
        )

        assertThat(flow.appId).isEqualTo("com.example.app")
        assertThat(flow.transportLayerProtocol).isEqualTo("TCP")
        assertThat(flow.appliedPolicy).isEqualTo(Policy.ALLOW)
        assertThat(flow.timeStart).isEqualTo(flow.timeEnd)
        assertThat(flow.packetCountEgress).isEqualTo(0L)
        assertThat(flow.packetCountIngress).isEqualTo(0L)
        assertThat(flow.dataIngress).isEqualTo(0L)
        assertThat(flow.dataEgress).isEqualTo(0L)
        assertThat(flow.tcpEstablishedReached).isFalse()
        assertThat(flow.protocols).isEmpty()
        assertThat(flow.timeEndAtLastSync).isEqualTo(0L)
    }

    @Test
    fun `convertMillisToRFC8601 produces a parseable timestamp for a fixed instant`() {
        val millis = 1_700_000_000_000L
        val formatted = LANFlow.convertMillisToRFC8601(millis)

        // Independent of the JVM's default time zone, it must denote the same instant.
        val parsed = OffsetDateTime.parse(formatted, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        assertThat(parsed.toInstant()).isEqualTo(Instant.ofEpochMilli(millis))
    }
}
