package org.distrinet.lanshield.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.distrinet.lanshield.database.model.LANShieldSession
import java.util.UUID


@Dao
interface LANShieldSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(vararg lanShieldSessions: LANShieldSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(lanShieldSession: LANShieldSession)

    @Update
    fun update(lanShieldSession: LANShieldSession)

    @Update
    fun updateAll(vararg lanShieldSessions: LANShieldSession)

    @Delete
    fun delete(lanShieldSession: LANShieldSession)

    @Query("SELECT * FROM lanshield_session WHERE timeEndAtLastSync != timeEnd")
    fun getAllShouldSync(): List<LANShieldSession>

    @Query("SELECT * FROM lanshield_session WHERE uuid IN (:ids)")
    fun getAllById(ids: List<UUID>): List<LANShieldSession>

    @Query("UPDATE lanshield_session SET timeEndAtLastSync = :timeEndLast WHERE uuid = :sessionId")
    fun updateSessionsSyncedTime(sessionId: UUID, timeEndLast: Long)
}