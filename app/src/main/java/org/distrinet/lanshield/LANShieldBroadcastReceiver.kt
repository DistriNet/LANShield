package org.distrinet.lanshield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.model.LanAccessPolicy
import org.distrinet.lanshield.vpnservice.LANShieldNotificationManager
import org.distrinet.lanshield.vpnservice.VPNService
import org.distrinet.lanshield.vpnservice.VPNServiceWorker
import javax.inject.Inject


@AndroidEntryPoint
class LANShieldBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var lanAccessPolicyDao: LanAccessPolicyDao

    @Inject
    lateinit var lanShieldNotificationManager: LANShieldNotificationManager

    @Inject
    lateinit var dataStore: DataStore<Preferences>

    override fun onReceive(context: Context?, intent: Intent?) {
        if(intent == null || context == null) return
        when(intent.action) {
            LANShieldIntentAction.UPDATE_LAN_POLICY.name -> handleUpdateLanPolicy(context, intent)
            Intent.ACTION_BOOT_COMPLETED -> handleBoot(context)
            else -> {
                Log.e(TAG, "Unknown intent action: ${intent.action}")
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun handleBoot(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            val autoStartEnabled = dataStore.data.first()[AUTOSTART_ENABLED] ?: false
            Log.d(TAG, "handleBoot: autoStartEnabled=$autoStartEnabled")
            if(autoStartEnabled) {
                VPNServiceWorker.enqueueStartVpnService(context)
            }
        }
   }

    @OptIn(DelicateCoroutinesApi::class)
    private fun handleUpdateLanPolicy(context: Context, intent: Intent) {
        val policyString = intent.getStringExtra("${context.packageName}.${LANShieldIntentExtra.POLICY.name}") ?: return
        val packageName = intent.getStringExtra("${context.packageName}.${LANShieldIntentExtra.PACKAGE_NAME.name}") ?: return
        val packageIsSystem = intent.getBooleanExtra("${context.packageName}.${LANShieldIntentExtra.PACKAGE_IS_SYSTEM.name}", false) ?: return
        val policy = Policy.valueOf(policyString)

        GlobalScope.launch(Dispatchers.Default) {
            val lanAccessPolicy = LanAccessPolicy(packageName, policy, isSystem = packageIsSystem, shouldSync = true)
            lanAccessPolicyDao.insert(lanAccessPolicy)
        }
        lanShieldNotificationManager.dismissNotification(packageName)
    }

}