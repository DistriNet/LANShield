package org.distrinet.lanshield

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.distrinet.lanshield.backendsync.OpenPortsWorker
import org.distrinet.lanshield.backendsync.SendToServerWorker
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
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

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

        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    // Permission obtained; continue the enable flow that was paused to ask for it.
                    proceedStartVPNService()
                } else {
                    onNotificationPermissionDenied()
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

        scheduleWorker(
            workerClass = SendToServerWorker::class.java,
            repeatInterval = BACKEND_SYNC_INTERVAL_DAYS,
            repeatIntervalTimeUnit = TimeUnit.DAYS,
            uniqueWorkName = "lanshieldSyncWorker")
        scheduleWorker(
            workerClass = OpenPortsWorker::class.java,
            repeatInterval = OPEN_PORT_SCAN_INTERVAL_HOURS,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            uniqueWorkName = "lanshieldScanOpenPortsWorker")

//        WorkManager.getInstance(this).cancelAllWork()
//        runWorkerInstantly<SendToServerWorker>()
    }

    private inline fun <reified T : ListenableWorker> runWorkerInstantly() {
        val request = OneTimeWorkRequestBuilder<T>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(this).enqueue(request)
    }

    private fun scheduleWorker(
        workerClass: Class<out ListenableWorker?>,
        repeatInterval: Long,
        repeatIntervalTimeUnit: TimeUnit, uniqueWorkName: String
    ) {
        val constraints: Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        val periodicWorkRequest = PeriodicWorkRequest.Builder(
            workerClass,
            repeatInterval,
            repeatIntervalTimeUnit
        ).setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        val workManager = WorkManager.getInstance(this)

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
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
        // Notification permission can be auto-revoked while the app is unused (and is only requested
        // during onboarding otherwise), yet the foreground-service banner and the LAN-traffic
        // allow/block prompts depend on it. Re-check on every enable and refuse to start without it.
        if (!hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedStartVPNService()
    }

    private fun proceedStartVPNService() {
        if (!hasVPNConsent()) {
            askVPNConsent()
        } else {
            try {
                startService(Intent(this, VPNService::class.java))
            } catch (_: SecurityException) {
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun onNotificationPermissionDenied() {
        // The VPN is intentionally not started; the switch stays off because it tracks the service
        // status. Tell the user why, and route to settings when the permission is permanently denied
        // (the request dialog no longer appears), so they aren't left with a switch that does nothing.
        Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            openAppNotificationSettings()
        }
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
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


