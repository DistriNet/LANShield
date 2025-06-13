package org.distrinet.lanshield.database.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.distrinet.lanshield.database.model.LANFlow.Companion.convertMillisToRFC8601
import org.json.JSONObject
import java.util.UUID


@Entity(tableName = "lanshield_session")
data class LANShieldSession(
    @PrimaryKey
    val uuid: UUID,
    val timeStart: Long,
    var timeEnd: Long,
    var timeEndAtLastSync: Long,
){

    fun toJSON(): JSONObject {
        val json = JSONObject()
        json.put("lanshield_session_uuid", uuid)
        json.put("time_start", convertMillisToRFC8601(timeStart))
        json.put("time_end", convertMillisToRFC8601(timeEnd))

        return json
    }


    companion object {
        fun createLANShieldSession(): LANShieldSession {
            return LANShieldSession(
                uuid = UUID.randomUUID(),
                timeStart = System.currentTimeMillis(),
                timeEnd = 0,
                timeEndAtLastSync = -1
            )
        }
    }
}




