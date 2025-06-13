package org.distrinet.lanshield.ui.settings

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
import org.distrinet.lanshield.AUTOSTART_ENABLED
import org.distrinet.lanshield.DEFAULT_POLICY_KEY
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.SYSTEM_APPS_POLICY_KEY
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val dataStore: DataStore<Preferences>) : ViewModel() {

    val isAutoStartEnabled = dataStore.data.map { it[AUTOSTART_ENABLED] ?: false }.distinctUntilChanged()


    val defaultPolicy =
        dataStore.data.map { Policy.valueOf(it[DEFAULT_POLICY_KEY] ?: Policy.DEFAULT.toString()) }
            .distinctUntilChanged()
    val systemAppsPolicy = dataStore.data.map {
        Policy.valueOf(
            it[SYSTEM_APPS_POLICY_KEY] ?: Policy.DEFAULT.toString()
        )
    }.distinctUntilChanged()


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

    fun onChangeSystemAppsPolicy(policy: Policy) {
        writeToDataStore(SYSTEM_APPS_POLICY_KEY, policy.toString())
    }

    fun onChangeAutoStart(enabled: Boolean) {
        writeToDataStore(AUTOSTART_ENABLED, enabled)
    }
}