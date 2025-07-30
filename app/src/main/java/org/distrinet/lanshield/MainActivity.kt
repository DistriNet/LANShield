package org.distrinet.lanshield

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.distrinet.lanshield.backendsync.OpenPortsWorker
import org.distrinet.lanshield.backendsync.SendToServerWorkerr
import org.distrinet.lanshield.ui.LANShieldApp
import org.distrinet.lanshield.ui.rememberLANShieldAppState
import org.distrinet.lanshield.ui.theme.LANShieldTheme
import org.distrinet.lanshield.vpnservice.VPNService
import java.util.concurrent.TimeUnit
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var vpnServiceActionRequest: MutableLiveData<VPN_SERVICE_ACTION>
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>

    @Inject
    lateinit var dataStore: DataStore<Preferences>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vpnPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    startService(Intent(this, VPNService::class.java))
                }
            }

        vpnServiceActionRequest.observe(this) {
            when (it) {
                VPN_SERVICE_ACTION.START_VPN -> startVPNService()
                VPN_SERVICE_ACTION.STOP_VPN -> stopVPNService()
                else -> {}
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            setDataStoreDefaults()
        }

        val introCompleted: Boolean = runBlocking {
            dataStore.data.map { it[INTRO_COMPLETED_KEY] ?: false }.distinctUntilChanged().first()
        }


        enableEdgeToEdge()
        setContent {
            val darkTheme = isSystemInDarkTheme()

            // Update the edge to edge configuration to match the theme
            // This is the same parameters as the default enableEdgeToEdge call, but we manually
            // resolve whether or not to show dark theme using uiState, since it can be different
            // than the configuration's dark theme value based on the user preference.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim,
                        darkScrim,
                    ) { darkTheme },
                )
                onDispose {}
            }

            LANShieldTheme(
                darkTheme = darkTheme,
                androidTheme = false,
                disableDynamicTheming = false,
            ) {
                val appState = rememberLANShieldAppState()
                LANShieldApp(appState, introCompleted = introCompleted)
            }
        }

//        scheduleWorker(
//            workerClass = SendToServerWorkerr::class.java,
//            repeatInterval = BACKEND_SYNC_INTERVAL_DAYS,
//            repeatIntervalTimeUnit = TimeUnit.MINUTES,
//            uniqueWorkName = "lanshieldSyncWorker")
//        scheduleWorker(
//            workerClass = OpenPortsWorker::class.java,
//            repeatInterval = OPEN_PORT_SCAN_INTERVAL_HOURS,
//            repeatIntervalTimeUnit = TimeUnit.HOURS,
//            uniqueWorkName = "lanshieldScanOpenPortsWorker")

        WorkManager.getInstance(this).cancelAllWork()
        runWorkerInstantly<SendToServerWorkerr>()
    }

    private inline fun <reified T : ListenableWorker> runWorkerInstantly() {
        val request = OneTimeWorkRequestBuilder<T>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(this).enqueue(request)
    }

    private fun scheduleWorker(workerClass: Class<out ListenableWorker?>,
                               repeatInterval: Long,
                               repeatIntervalTimeUnit: TimeUnit, uniqueWorkName: String) {
        val constraints: Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        val periodicWorkRequest = PeriodicWorkRequest.Builder(
            workerClass,
            repeatInterval,
            repeatIntervalTimeUnit
        ).setConstraints(constraints).build()

        val workManager = WorkManager.getInstance(this)

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }



    private suspend fun setDataStoreDefaults() {
        dataStore.edit { preferences ->

            if (preferences[INTRO_COMPLETED_KEY] == null) {
                preferences[INTRO_COMPLETED_KEY] = false
            }

            if (preferences[DEFAULT_POLICY_KEY] == null) {
                preferences[DEFAULT_POLICY_KEY] = Policy.ALLOW.name
            }

            if (preferences[SYSTEM_APPS_POLICY_KEY] == null) {
                preferences[SYSTEM_APPS_POLICY_KEY] = Policy.ALLOW.name
            }

            if (preferences[SHARE_LAN_METRICS_KEY] == null) {
                preferences[SHARE_LAN_METRICS_KEY] = false
            }

            if (preferences[SHARE_APP_USAGE_KEY] == null) {
                preferences[SHARE_APP_USAGE_KEY] = false
            }

            if (preferences[AUTOSTART_ENABLED] == null) {
                preferences[AUTOSTART_ENABLED] = true
            }
        }
    }


    private fun startVPNService() {
        if (!hasVPNConsent()) {
            askVPNConsent()
        } else {
            try {
                startService(Intent(this, VPNService::class.java))
            } catch (_: SecurityException) {
            }
        }

    }

    private fun stopVPNService() {
        val vpnIntent = Intent(this, VPNService::class.java).apply {
            action = VPNService.STOP_VPN_SERVICE
        }
        startService(vpnIntent)
    }

    private fun askVPNConsent() {

        val intent = VpnService.prepare(this)
        intent?.let {
            vpnPermissionLauncher.launch(it)
        }
    }

    private fun hasVPNConsent(): Boolean {
        return VpnService.prepare(this) == null
    }
}


private val lightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val darkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)


