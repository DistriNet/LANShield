package org.distrinet.lanshield.backendsync

import android.app.usage.UsageStats
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.distrinet.lanshield.ACL_SUCCESS
import org.distrinet.lanshield.ADD_ACL
import org.distrinet.lanshield.ADD_APP_USAGE
import org.distrinet.lanshield.ADD_FLOWS
import org.distrinet.lanshield.ADD_LANSHIELD_SESSION
import org.distrinet.lanshield.APP_INSTALLATION_UUID
import org.distrinet.lanshield.APP_USAGE_SUCCESS
import org.distrinet.lanshield.BACKEND_URL
import org.distrinet.lanshield.BuildConfig
import org.distrinet.lanshield.GET_APP_INSTALLATION_UUID
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.SHARE_APP_USAGE_KEY
import org.distrinet.lanshield.SHARE_LAN_METRICS_KEY
import org.distrinet.lanshield.SHOULD_SYNC
import org.distrinet.lanshield.TAG
import org.distrinet.lanshield.TIME_OF_LAST_SYNC
import org.distrinet.lanshield.database.dao.FlowDao
import org.distrinet.lanshield.database.dao.LANShieldSessionDao
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.database.model.LANShieldSession
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit


@HiltWorker
class SendToServerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    @Assisted private val flowDao: FlowDao,
    @Assisted private val lanAccessPolicyDao: LanAccessPolicyDao,
    @Assisted private val lanShieldSessionDao: LANShieldSessionDao,
    @Assisted private val dataStore: DataStore<Preferences>,
    private val appUsageStats: AppUsageStats = AppUsageStats(),
    private val queue: RequestQueue = Volley.newRequestQueue(context),
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val shareLanMetricsEnabled =
            dataStore.data.map { it[SHARE_LAN_METRICS_KEY] ?: false }.distinctUntilChanged().first()
        if (!shareLanMetricsEnabled) {
            return Result.success()
        }

        invokeBackendForSync()
        return Result.success()
    }

    private fun invokeBackendForSync() {
        apiRequest(Request.Method.GET, SHOULD_SYNC, null)
    }

    private suspend fun sendRequestsRequiringUUID() {
        CoroutineScope(Dispatchers.IO).launch {
            val shareAppUsageEnabled =
                dataStore.data.map { it[SHARE_APP_USAGE_KEY] ?: false }.distinctUntilChanged().first()
            if (shareAppUsageEnabled) {
                var timeOfLastSync = dataStore.data.map { it[TIME_OF_LAST_SYNC] }.first()
                if (timeOfLastSync == null) {
                    timeOfLastSync = Calendar.getInstance().timeInMillis
                    timeOfLastSync -= TimeUnit.DAYS.toMillis(1)
                }
                val appUsageStatistics =
                    appUsageStats.getUsageStatsList(applicationContext, timeOfLastSync)
                syncAppUsage(appUsageStatistics)
            }

            val batchSize = 1000
            val totalCount = flowDao.countNotSyncedFlows()
            val batches = (totalCount + batchSize - 1) / batchSize  // ceiling division

            for (i in (batches - 1) downTo 0) {
                val offset = i * batchSize
                val flows = flowDao.getNotSyncedFlowsPaged(batchSize, offset)
                syncFlows(flows)
            }

            val allowedUnsyncedApps = lanAccessPolicyDao.getAllUnsyncedPolicy(Policy.ALLOW)
            val blockedUnsyncedApps = lanAccessPolicyDao.getAllUnsyncedPolicy(Policy.BLOCK)
            val defaultUnsyncedApps = lanAccessPolicyDao.getAllUnsyncedPolicy(Policy.DEFAULT)
            syncACL(allowedUnsyncedApps, blockedUnsyncedApps, defaultUnsyncedApps)

            val sessions = lanShieldSessionDao.getAllShouldSync()
            syncLanShieldSessions(sessions)
        }
    }

    private fun setAppInstallationUUID() {
        val body = JSONObject()
        body.put("device_brand", Build.BRAND)
        body.put("device_model", Build.MODEL)
        body.put("device_sdk", Build.VERSION.SDK_INT)
        apiRequest(Request.Method.POST, GET_APP_INSTALLATION_UUID, body)
    }

    private fun apiRequest(method: Int, endpoint: String, jsonBody: JSONObject?) {
        val url = BACKEND_URL + endpoint
        val jsonRequest = JsonObjectRequest(method, url, jsonBody, { response ->
            try {
                when (endpoint) {
                    GET_APP_INSTALLATION_UUID -> handleAppInstallationUUIDResponse(response)
                    ADD_ACL -> handleACLResponse(response)
                    ADD_APP_USAGE -> handleAppUsageResponse(response)
                    ADD_FLOWS -> handleAddFlowResponse(response)
                    SHOULD_SYNC -> handleShouldSyncResponse(response)
                    ADD_LANSHIELD_SESSION -> handleAddLanShieldSessionsResponse(response)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                crashReporter.recordException(e)
            }
        }, { error ->
            //NO RESPONSE
            crashReporter.recordException(error)
            Log.e(TAG, "ERROR $error")
        })
        jsonRequest.setShouldRetryConnectionErrors(true)
        jsonRequest.setShouldRetryServerErrors(true)
        jsonRequest.setRetryPolicy(
            DefaultRetryPolicy(
                5000, //5 SECONDS
                3, //3 retries
                1.5f
            )
        )
        queue.add(jsonRequest)

    }

    private fun handleShouldSyncResponse(response: JSONObject) {
        var shouldSync = false
        var appVersionsAllowed = listOf<String>()
        try {
            shouldSync = response.getBoolean("should_sync")
            appVersionsAllowed = response.getString("app_versions").split(",")
        } catch (e: JSONException) {
            Log.e(TAG, e.toString())
            crashReporter.recordException(e)
        }
        if (shouldSync && isVersionAllowed(appVersionsAllowed)) {
            runBlocking {
                sendRoutineRequests()
            }
        }

    }

    private fun isVersionAllowed(appVersionsAllowed: List<String>): Boolean {
        val versionName: String = BuildConfig.VERSION_NAME
        return appVersionsAllowed.contains(versionName)
    }

    private suspend fun sendRoutineRequests() {
        if (getAppInstallationUUID() == null) {
            setAppInstallationUUID()
        } else {
            sendRequestsRequiringUUID()
        }
    }

    private fun handleAppUsageResponse(response: JSONObject) {
        try {
            val message = response.getString("message")
            if (message == APP_USAGE_SUCCESS) {
                runBlocking {
                    dataStore.edit {
                        it[TIME_OF_LAST_SYNC] = Calendar.getInstance().timeInMillis
                    }
                }
            }
        }
        catch (e: Exception) {
            Log.e(TAG, e.toString())
            crashReporter.recordException(e)
        }

    }

    private fun handleAppInstallationUUIDResponse(response: JSONObject) {
        try {
            val uuidsString = response.getString("app_installation_uuid")
            val validateUUID: UUID = UUID.fromString(uuidsString)
            runBlocking {
                dataStore.edit { it[APP_INSTALLATION_UUID] = uuidsString }
                sendRequestsRequiringUUID()
            }
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "UUID is of wrong format $e")
            crashReporter.recordException(e)
        }
        catch (e: JSONException) {
            Log.e(TAG, e.toString())
            crashReporter.recordException(e)
        }

    }

    private suspend fun validateUUID(uuid: String): Boolean {
        return getAppInstallationUUID() == uuid
    }

    private fun handleAddFlowResponse(response: JSONObject) {
        val appUUID = response.getString("app_installation_uuid")
        CoroutineScope(Dispatchers.IO).launch {
            if (!validateUUID(appUUID)) return@launch
            val flows = response.getJSONArray("flows")
            val flowIds = mutableListOf<UUID>()
            for (i in 0 until flows.length()) {
                val app = flows.getJSONObject(i)

                try {
                    val flowId = UUID.fromString(app.getString("flow_uuid"))
                    flowIds.add(flowId)
                    val timeEnd = app.getString("time_end")
                    val zonedDateTime =
                        ZonedDateTime.parse(timeEnd, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    val instant = zonedDateTime.toInstant()
                    val timeEndLong = instant.toEpochMilli()
                    flowDao.updateFlowsSyncedTime(flowId, timeEndLong)
                } catch (e: Exception) {
                    crashReporter.recordException(e)
                    Log.e(TAG, e.toString())
                }

            }
            flowDao.removeAllScheduledForDeletionById(flowIds)
        }
    }

    private fun handleACLResponse(response: JSONObject) {
        try {
            val message = response.getString("message")
            if (message == ACL_SUCCESS) {
                CoroutineScope(Dispatchers.IO).launch {
                    lanAccessPolicyDao.setAllSynced()
                }
            }
        }
        catch (e: Exception) {
            crashReporter.recordException(e)
            Log.e(TAG, e.toString())
        }

    }


    private fun handleAddLanShieldSessionsResponse(response: JSONObject) {
        val appUUID = response.getString("app_installation_uuid")
        CoroutineScope(Dispatchers.IO).launch {
            if (!validateUUID(appUUID)) return@launch
            val sessions = response.getJSONArray("lanshield_sessions")
            for (i in 0 until sessions.length()) {
                try {
                    val session = sessions.getJSONObject(i)
                    val sessionId = UUID.fromString(session.getString("lanshield_session_uuid"))
                    val timeEnd = session.getString("time_end")
                    val zonedDateTime =
                        ZonedDateTime.parse(timeEnd, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    val instant = zonedDateTime.toInstant()
                    val timeEndLong = instant.toEpochMilli()
                    lanShieldSessionDao.updateSessionsSyncedTime(sessionId, timeEndLong)
                } catch (e: Exception) {
                    crashReporter.recordException(e)
                    Log.e(TAG, e.toString())
                }
            }
        }
    }

    private suspend fun getAppInstallationUUID(): String? {
        return dataStore.data.map { it[APP_INSTALLATION_UUID] }.first()
    }


    private suspend fun syncFlows(flows: List<LANFlow>?) {
        if (flows.isNullOrEmpty()) return
        val appInstallationUUID: String = getAppInstallationUUID() ?: return

        val addFlowJsonBody = JSONObject()
        addFlowJsonBody.put("app_installation_uuid", appInstallationUUID)
        val flowsJsonArray = JSONArray()
        for (flow in flows) {
            val flowJson = flow.toJSON()
            flowsJsonArray.put(flowJson)
        }
        addFlowJsonBody.put("flows", flowsJsonArray)

        apiRequest(Request.Method.POST, ADD_FLOWS, addFlowJsonBody)


    }

    private suspend fun syncACL(
        allowedUnsyncedApps: List<String>?,
        blockedUnsyncedApps: List<String>?,
        defaultUnsyncedApps: List<String>?
    ) {

        if (allowedUnsyncedApps.isNullOrEmpty() && blockedUnsyncedApps.isNullOrEmpty() && defaultUnsyncedApps.isNullOrEmpty()) return
        val appInstallationUUID = getAppInstallationUUID() ?: return

        val jsonBody = JSONObject()

        jsonBody.put("app_installation_uuid", appInstallationUUID)


        jsonBody.put("allowlist", JSONArray(allowedUnsyncedApps.orEmpty()))
        jsonBody.put("blocklist", JSONArray(blockedUnsyncedApps.orEmpty()))
        jsonBody.put("defaultlist", JSONArray(defaultUnsyncedApps.orEmpty()))

        apiRequest(Request.Method.POST, ADD_ACL, jsonBody)

    }

    private suspend fun syncLanShieldSessions(
        sessions: List<LANShieldSession>
    ) {
        if(sessions.isEmpty()) return
        val appInstallationUUID = getAppInstallationUUID() ?: return

        val jsonBody = JSONObject()
        val sessionsJSONArray = JSONArray()

        for (session in sessions) {
            val sessionJson = session.toJSON()
            sessionsJSONArray.put(sessionJson)
        }
        jsonBody.put("app_installation_uuid", appInstallationUUID)
        jsonBody.put("lanshield_sessions", sessionsJSONArray)

        apiRequest(Request.Method.POST, ADD_LANSHIELD_SESSION, jsonBody)
    }

    private suspend fun syncAppUsage(appUsage: List<UsageStats>) {
        val jsonBody = JSONObject()
        jsonBody.put("app_installation_uuid", getAppInstallationUUID())
        jsonBody.put("usage_stats", AppUsageStats.toJSONArray(appUsage, applicationContext))

        apiRequest(Request.Method.POST, ADD_APP_USAGE, jsonBody)
    }
}
