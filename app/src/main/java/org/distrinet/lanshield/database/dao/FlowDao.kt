package org.distrinet.lanshield.database.dao


import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.distrinet.lanshield.database.model.FlowAverage
import org.distrinet.lanshield.database.model.LANFlow
import java.util.UUID

@Dao
interface FlowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFlow(LANFlow: LANFlow)

    @Query("SELECT * FROM Flow")
    fun getAllFlows(): Flow<List<LANFlow>>

    @Query("SELECT * FROM Flow WHERE uuid = :uuid")
    fun getFlowById(uuid: UUID): LANFlow?

    @Update
    fun updateFlow(LANFlow: LANFlow)

    @Delete
    fun deleteFlow(LANFlow: LANFlow)

    @Query("SELECT * FROM Flow WHERE appId = :appId ORDER BY timeEnd DESC")
    fun getFlowsByAppId(appId: String): Flow<List<LANFlow>>

    @Query("SELECT appId FROM Flow GROUP BY appId")
    fun getAppIdsWithFlow(): LiveData<List<String>>

    @Query("DELETE FROM Flow WHERE appId = :appId")
    fun deleteFlowsWithAppId(appId: String)

    @Query(
        """
    SELECT appId, 
           SUM(dataIngress) as totalBytesIngress, 
           SUM(dataEgress) as totalBytesEgress,
           SUM(CASE WHEN timeEnd >= :twentyFourHoursAgo THEN dataIngress ELSE 0 END) as totalBytesIngressLast24h,
           SUM(CASE WHEN timeEnd >= :twentyFourHoursAgo THEN dataEgress ELSE 0 END) as totalBytesEgressLast24h,
           MAX(timeEnd) as latestTimeEnd
    FROM flow
    GROUP BY appId
    ORDER BY latestTimeEnd DESC
    """
    )
    fun getFlowAverages(twentyFourHoursAgo: Long): Flow<List<FlowAverage>>

    @Query(
        """
            SELECT * FROM flow
            WHERE timeEndAtLastSync < timeEnd 
    """
    )
    fun getUnsyncedFlows(): Flow<List<LANFlow>>

    @Query(
        "UPDATE flow SET timeEndAtLastSync = :timeEndLast WHERE uuid = :flowId"
    )
    fun updateFlowsSyncedTime(flowId:String,timeEndLast: Long)
}