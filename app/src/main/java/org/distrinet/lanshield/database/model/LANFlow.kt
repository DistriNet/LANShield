package org.distrinet.lanshield.database.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import org.distrinet.lanshield.PACKAGE_NAME_UNKNOWN
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.database.dao.InetSocketAddressConverter
import org.distrinet.lanshield.database.dao.StringListConverter
import org.json.JSONObject
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID


@Entity(tableName = "flow")
@TypeConverters(InetSocketAddressConverter::class, StringListConverter::class)
data class LANFlow(
    val appId: String?,
    @PrimaryKey
    val uuid: UUID,
    @TypeConverters(InetSocketAddressConverter::class)
    val remoteEndpoint: InetSocketAddress,
    @TypeConverters(InetSocketAddressConverter::class)
    val localEndpoint: InetSocketAddress,
    val transportLayerProtocol: String,
    val timeStart: Long,
    var timeEnd: Long,
    var packetCountEgress: Long,
    var packetCountIngress: Long,
    var dataIngress: Long,
    var dataEgress: Long,
    var tcpEstablishedReached: Boolean,
    var appliedPolicy: Policy,
    @TypeConverters(StringListConverter::class)
    var protocols: List<String>,
    val timeEndAtLastSync: Long,
    val scheduledForDeletion: Boolean = false,
    var dpiReport: String? = null,
    var dpiProtocol: String? = null
) {

    fun toJSON(): JSONObject {
        val json = JSONObject()
        json.put("flow_uuid", uuid)
        json.put("app_id", appId ?: PACKAGE_NAME_UNKNOWN)
        json.put("time_start", convertMillisToRFC8601(timeStart))
        json.put("time_end", convertMillisToRFC8601(timeEnd))
        json.put("remote_ip", remoteEndpoint.toString().removePrefix("/"))
        json.put("remote_port", remoteEndpoint.port)
        json.put("local_ip", localEndpoint.toString().removePrefix("/"))
        json.put("local_port", localEndpoint.port)
        json.put("transport_layer_protocol", transportLayerProtocol)
        json.put("packet_count_egress", packetCountEgress)
        json.put("data_egress", dataEgress)
        json.put("packet_count_ingress", packetCountIngress)
        json.put("data_ingress", dataIngress)
        json.put("detected_protocols", protocols.joinToString(","))
        json.put("time_end_at_last_sync", timeEndAtLastSync)
        json.put("dpi_report", dpiReport ?: "{}")
        json.put("dpi_protocol", dpiProtocol ?: "")

        if (transportLayerProtocol.contentEquals("TCP")) {
            json.put("tcp_established_reached", tcpEstablishedReached)
        }

        return json
    }

    fun increaseEgress(amountPackets: Long, amountBytes: Long) {
        synchronized(this) {
            packetCountEgress += amountPackets
            dataEgress += amountBytes
            timeEnd = System.currentTimeMillis()
        }
    }

    fun increaseIngress(amountPackets: Long, amountBytes: Long) {
        synchronized(this) {
            packetCountIngress += amountPackets
            dataIngress += amountBytes
            timeEnd = System.currentTimeMillis()
        }
    }

    companion object {

        fun createFlow(
            appId: String?,
            remoteEndpoint: InetSocketAddress,
            localEndpoint: InetSocketAddress,
            transportLayerProtocol: String,
            appliedPolicy: Policy,
        ): LANFlow {
            val time = System.currentTimeMillis()

            return LANFlow(
                appId = appId,
                uuid = UUID.randomUUID(),
                remoteEndpoint = remoteEndpoint,
                localEndpoint = localEndpoint,
                transportLayerProtocol = transportLayerProtocol,
                timeStart = time,
                timeEnd = time,
                packetCountEgress = 0L,
                packetCountIngress = 0L,
                dataIngress = 0L,
                dataEgress = 0L,
                tcpEstablishedReached = false,
                appliedPolicy = appliedPolicy,
                protocols = listOf(),
                timeEndAtLastSync = 0
            )
        }


        fun convertMillisToRFC8601(millis: Long): String {
            val instant = Instant.ofEpochMilli(millis)
            val formatter =
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
            return formatter.format(instant)
        }
    }
}


data class FlowAverage(
    var totalBytesIngress: Long, var totalBytesEgress: Long,
    var totalBytesIngressLast24h: Long, var totalBytesEgressLast24h: Long,
    var appId: String, var latestTimeEnd: Long
)