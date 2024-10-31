package org.distrinet.lanshield.ui.intro

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.distrinet.lanshield.DEFAULT_POLICY_KEY
import org.distrinet.lanshield.INTRO_COMPLETED_KEY
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.SHARE_APP_USAGE_KEY
import org.distrinet.lanshield.SHARE_LAN_METRICS_KEY
import org.distrinet.lanshield.vpnservice.LANShieldNotificationManager
import javax.inject.Inject

@HiltViewModel
class IntroViewModel  @Inject constructor(
    val dataStore: DataStore<Preferences>,
    val lanShieldNotificationManager: LANShieldNotificationManager) : ViewModel()  {

    val shareLanMetricsEnabled =
        dataStore.data.map { it[SHARE_LAN_METRICS_KEY] ?: false }.distinctUntilChanged()
    val shareAppUsageEnabled =
        dataStore.data.map { it[SHARE_APP_USAGE_KEY] ?: false }.distinctUntilChanged()
    val defaultPolicy =
        dataStore.data.map { Policy.valueOf(it[DEFAULT_POLICY_KEY] ?: Policy.DEFAULT.toString()) }
            .distinctUntilChanged()

    private fun <T> writeToDataStore(key: Preferences.Key<T>, value: T) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dataStore.edit {
                    it[key] = value
                }
            }
        }
    }

    fun onChangeDefaultPolicy(policy: Policy) {
        writeToDataStore(DEFAULT_POLICY_KEY, policy.toString())
    }

    fun onChangeShareLanMetrics(enabled: Boolean) {
        writeToDataStore(SHARE_LAN_METRICS_KEY, enabled)
    }

    fun onChangeShareAppUsage(enabled: Boolean) {
        writeToDataStore(SHARE_APP_USAGE_KEY, enabled)
    }

    fun onChangeAppIntro(finished: Boolean) {
        writeToDataStore(INTRO_COMPLETED_KEY, finished)
    }

    fun createNotificationChannels() {
        lanShieldNotificationManager.createNotificationChannels()
    }
}