package org.distrinet.lanshield.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.distrinet.lanshield.database.model.OpenPorts

@Dao
interface OpenPortsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(vararg openPorts: OpenPorts)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(openPortsList: List<OpenPorts>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(openPorts: OpenPorts)

    @Delete
    fun delete(openPorts: OpenPorts)

    @Query("DELETE FROM open_ports")
    fun deleteAll()

    @Update
    suspend fun update(vararg openPorts: OpenPorts)

    @Query("SELECT * FROM open_ports WHERE shouldSync = 1")
    fun getAllShouldSync(): List<OpenPorts>

}