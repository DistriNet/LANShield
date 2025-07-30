package org.distrinet.lanshield.database.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.distrinet.lanshield.database.model.LANFlow.Companion.convertMillisToRFC8601
import org.json.JSONArray
import org.json.JSONObject
import java.util.SortedSet
import java.util.TreeSet
import java.util.UUID

@Entity(tableName = "open_ports")
data class OpenPorts(
    @PrimaryKey
    val uuid: UUID,
    val packageLabel: String,
    val packageName: String,
    val udpPorts: SortedSet<Int>,
    val tcpPorts: SortedSet<Int>,
    var timeOpenPortsObserved: Long,
    var shouldSync: Boolean,
    var scheduledForDeletion: Boolean
) : Comparable<OpenPorts>
    {

    override fun compareTo(other: OpenPorts): Int {
        return packageLabel.compareTo(other.packageLabel, ignoreCase = true)
    }

    fun toJSON(): JSONObject {
        val json = JSONObject()
        json.put("open_ports_uuid", uuid)
        json.put("time_open_ports_observed", convertMillisToRFC8601(timeOpenPortsObserved))
        json.put("package_name", packageName.substringBefore(":"))
        json.put("package_label", packageLabel)
        val udpArray = JSONArray(udpPorts)
        val tcpArray = JSONArray(tcpPorts)
        json.put("udp_ports", udpArray)
        json.put("tcp_ports", tcpArray)

        return json
    }

        companion object {
            fun createInstance(packageName: String, packageLabel: String) : OpenPorts {
                return OpenPorts(
                    packageLabel = packageLabel,
                    packageName = packageName,
                    tcpPorts = TreeSet(),
                    udpPorts = TreeSet(),
                    shouldSync = true,
                    scheduledForDeletion = false,
                    uuid = UUID.randomUUID(),
                    timeOpenPortsObserved = System.currentTimeMillis()
                )
            }
        }

    }

