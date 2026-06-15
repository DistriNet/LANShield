package org.distrinet.lanshield.backendsync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.distrinet.lanshield.ACL_SUCCESS
import org.distrinet.lanshield.ADD_ACL
import org.distrinet.lanshield.ADD_APP_USAGE
import org.distrinet.lanshield.ADD_FLOWS
import org.distrinet.lanshield.ADD_LANSHIELD_SESSION
import org.distrinet.lanshield.ADD_OPEN_PORTS
import org.distrinet.lanshield.APP_INSTALLATION_UUID
import org.distrinet.lanshield.APP_USAGE_SUCCESS
import org.distrinet.lanshield.GET_APP_INSTALLATION_UUID
import org.distrinet.lanshield.OPEN_PORTS_SUCCESS
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.SHARE_APP_USAGE_KEY
import org.distrinet.lanshield.SHARE_LAN_METRICS_KEY
import org.distrinet.lanshield.TAG
import org.distrinet.lanshield.TIME_OF_LAST_SYNC
import org.distrinet.lanshield.crashreport.crashReporter
import org.distrinet.lanshield.database.dao.FlowDao
import org.distrinet.lanshield.database.dao.LANShieldSessionDao
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.dao.OpenPortsDao
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit


@HiltWorker
class SendToServerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val flowDao: FlowDao,
    private val lanAccessPolicyDao: LanAccessPolicyDao,
    private val lanShieldSessionDao: LANShieldSessionDao,
    private val openPortsDao: OpenPortsDao,
    private val dataStore: DataStore<Preferences>,
    private val appUsageStats: AppUsageStats,
    private val backendApi: BackendApi,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val shareLanMetricsEnabled =
            dataStore.data.map { it[SHARE_LAN_METRICS_KEY] ?: false }.distinctUntilChanged().first()
        if (!shareLanMetricsEnabled) {
            return Result.success()
        }

        return try {
            sendRoutineRequests()
            Result.success()
        } catch (e: IOException) {
            // Network/server failure: let WorkManager retry with backoff.
            Log.e(TAG, "Backend sync failed, will retry: $e")
            crashReporter.recordException(e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Backend sync failed: $e")
            crashReporter.recordException(e)
            Result.failure()
        }
    }

    private suspend fun sendRoutineRequests() {
        val appInstallationUUID = getAppInstallationUUID() ?: registerAppInstallation()
        sendRequestsRequiringUUID(appInstallationUUID)
    }

    private suspend fun registerAppInstallation(): String {
        val body = JSONObject()
        body.put("device_brand", Build.BRAND)
        body.put("device_model", Build.MODEL)
        body.put("device_sdk", Build.VERSION.SDK_INT)

        val response = backendApi.postJson(GET_APP_INSTALLATION_UUID, body)
        val uuid = response.getString("app_installation_uuid")
        UUID.fromString(uuid) // validate format; throws if malformed
        dataStore.edit { it[APP_INSTALLATION_UUID] = uuid }
        return uuid
    }

    private suspend fun sendRequestsRequiringUUID(appInstallationUUID: String) {
        val shareAppUsageEnabled =
            dataStore.data.map { it[SHARE_APP_USAGE_KEY] ?: false }.distinctUntilChanged().first()
        if (shareAppUsageEnabled) {
            var timeOfLastSync = dataStore.data.map { it[TIME_OF_LAST_SYNC] }.first()
            if (timeOfLastSync == null) {
                timeOfLastSync = Calendar.getInstance().timeInMillis - TimeUnit.DAYS.toMillis(1)
            }
            val appUsageStatistics =
                appUsageStats.getUsageStatsList(applicationContext, timeOfLastSync)
            syncAppUsage(appInstallationUUID, appUsageStatistics)
        }

        syncFlows(appInstallationUUID)
        syncACL(appInstallationUUID)
        syncLanShieldSessions(appInstallationUUID)
        syncOpenPorts(appInstallationUUID)
    }

    /**
     * Uploads not-yet-synced flows in bounded batches. Paging uses a keyset cursor on the immutable
     * `uuid` primary key (not LIMIT/OFFSET), so marking flows as synced between batches cannot cause
     * rows to be skipped or duplicated, and only one batch is ever held in memory.
     */
    private suspend fun syncFlows(appInstallationUUID: String) {
        var cursor = UUID(0L, 0L)
        while (true) {
            val batch = flowDao.getNotSyncedFlowsAfter(cursor, BATCH_SIZE)
            if (batch.isEmpty()) break

            val response = backendApi.postStreaming(ADD_FLOWS) { writer ->
                writer.beginObject()
                writer.name("app_installation_uuid").value(appInstallationUUID)
                writer.name("flows").beginArray()
                batch.forEach { it.writeJson(writer) }
                writer.endArray()
                writer.endObject()
            }

            // Always advance past this batch so an unacknowledged flow can't loop forever.
            cursor = batch.last().uuid

            if (response.optString("app_installation_uuid") != appInstallationUUID) continue

            val flows = response.getJSONArray("flows")
            val flowIds = ArrayList<UUID>(flows.length())
            for (i in 0 until flows.length()) {
                try {
                    val app = flows.getJSONObject(i)
                    val flowId = UUID.fromString(app.getString("flow_uuid"))
                    flowIds.add(flowId)
                    flowDao.updateFlowsSyncedTime(flowId, parseTimeEnd(app.getString("time_end")))
                } catch (e: Exception) {
                    crashReporter.recordException(e)
                    Log.e(TAG, e.toString())
                }
            }
            flowDao.removeAllScheduledForDeletionById(flowIds)
        }
    }

    private suspend fun syncACL(appInstallationUUID: String) {
        val allowedUnsyncedApps = lanAccessPolicyDao.getAllUnsyncedPolicy(Policy.ALLOW)
        val blockedUnsyncedApps = lanAccessPolicyDao.getAllUnsyncedPolicy(Policy.BLOCK)
        val defaultUnsyncedApps = lanAccessPolicyDao.getAllUnsyncedPolicy(Policy.DEFAULT)
        if (allowedUnsyncedApps.isEmpty() && blockedUnsyncedApps.isEmpty() && defaultUnsyncedApps.isEmpty()) return

        val jsonBody = JSONObject()
        jsonBody.put("app_installation_uuid", appInstallationUUID)
        jsonBody.put("allowlist", JSONArray(allowedUnsyncedApps))
        jsonBody.put("blocklist", JSONArray(blockedUnsyncedApps))
        jsonBody.put("defaultlist", JSONArray(defaultUnsyncedApps))

        val response = backendApi.postJson(ADD_ACL, jsonBody)
        if (response.optString("message") == ACL_SUCCESS) {
            lanAccessPolicyDao.setAllSynced()
        }
    }

    private suspend fun syncLanShieldSessions(appInstallationUUID: String) {
        val sessions = lanShieldSessionDao.getAllShouldSync()
        if (sessions.isEmpty()) return

        val sessionsJSONArray = JSONArray()
        for (session in sessions) {
            sessionsJSONArray.put(session.toJSON())
        }
        val jsonBody = JSONObject()
        jsonBody.put("app_installation_uuid", appInstallationUUID)
        jsonBody.put("lanshield_sessions", sessionsJSONArray)

        val response = backendApi.postJson(ADD_LANSHIELD_SESSION, jsonBody)
        if (response.optString("app_installation_uuid") != appInstallationUUID) return

        val responseSessions = response.getJSONArray("lanshield_sessions")
        for (i in 0 until responseSessions.length()) {
            try {
                val session = responseSessions.getJSONObject(i)
                val sessionId = UUID.fromString(session.getString("lanshield_session_uuid"))
                lanShieldSessionDao.updateSessionsSyncedTime(
                    sessionId,
                    parseTimeEnd(session.getString("time_end"))
                )
            } catch (e: Exception) {
                crashReporter.recordException(e)
                Log.e(TAG, e.toString())
            }
        }
    }

    private suspend fun syncOpenPorts(appInstallationUUID: String) {
        val openPortsWithApps = openPortsDao.getAllShouldSync()
        if (openPortsWithApps.isEmpty()) return

        val openPortsJSONArray = JSONArray()
        for (openPorts in openPortsWithApps) {
            openPortsJSONArray.put(openPorts.toJSON())
        }
        val jsonBody = JSONObject()
        jsonBody.put("app_installation_uuid", appInstallationUUID)
        jsonBody.put("open_ports", openPortsJSONArray)

        val response = backendApi.postJson(ADD_OPEN_PORTS, jsonBody)
        if (response.optString("message") == OPEN_PORTS_SUCCESS) {
            openPortsDao.deleteAll()
        }
    }

    private suspend fun syncAppUsage(
        appInstallationUUID: String,
        appUsage: List<android.app.usage.UsageStats>
    ) {
        val jsonBody = JSONObject()
        jsonBody.put("app_installation_uuid", appInstallationUUID)
        jsonBody.put("usage_stats", AppUsageStats.toJSONArray(appUsage, applicationContext))

        val response = backendApi.postJson(ADD_APP_USAGE, jsonBody)
        if (response.optString("message") == APP_USAGE_SUCCESS) {
            dataStore.edit { it[TIME_OF_LAST_SYNC] = Calendar.getInstance().timeInMillis }
        }
    }

    private suspend fun getAppInstallationUUID(): String? {
        return dataStore.data.map { it[APP_INSTALLATION_UUID] }.first()
    }

    private fun parseTimeEnd(timeEnd: String): Long =
        ZonedDateTime.parse(timeEnd, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()

    companion object {
        private const val BATCH_SIZE = 500
    }
}
