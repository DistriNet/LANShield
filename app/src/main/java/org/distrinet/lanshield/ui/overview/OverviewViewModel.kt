package org.distrinet.lanshield.ui.overview


import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.distrinet.lanshield.AUTOSTART_ENABLED
import org.distrinet.lanshield.DEFAULT_POLICY_KEY
import org.distrinet.lanshield.VPN_SERVICE_ACTION
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.VPN_ALWAYS_ON_STATUS
import javax.inject.Inject

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val vpnServiceActionRequest: MutableLiveData<VPN_SERVICE_ACTION>,
    private val _vpnServiceStatus: MutableLiveData<VPN_SERVICE_STATUS>,
    private val _vpnServiceAlwaysOnStatus: MutableLiveData<VPN_ALWAYS_ON_STATUS>,
    private val dataStore: DataStore<Preferences>,
    private val lanAccessPolicyDao: LanAccessPolicyDao,
) : ViewModel() {

    val defaultPolicy =
        dataStore.data.map { Policy.valueOf(it[DEFAULT_POLICY_KEY] ?: Policy.DEFAULT.toString()) }
            .distinctUntilChanged()
    val amountAllowedApps = lanAccessPolicyDao.countByPolicy(Policy.ALLOW)
    val amountBlockedApps = lanAccessPolicyDao.countByPolicy(Policy.BLOCK)


    private fun makeVPNServiceRequest(action: VPN_SERVICE_ACTION) {
        vpnServiceActionRequest.postValue(action)
    }

    val vpnServiceStatus: LiveData<VPN_SERVICE_STATUS> = _vpnServiceStatus
    val vpnServiceAlwaysOnStatus: LiveData<VPN_ALWAYS_ON_STATUS> = _vpnServiceAlwaysOnStatus

    val isSwitchChecked = MutableStateFlow(false)
    val vpnConnectionRequested = MutableStateFlow(false)


    fun onLANShieldSwitchChanged(switchEnabled: Boolean) {
        if (switchEnabled) {
            isSwitchChecked.value = true
            vpnConnectionRequested.value = true

        } else {
            isSwitchChecked.value = false
            makeVPNServiceRequest(VPN_SERVICE_ACTION.STOP_VPN)
        }
    }

    fun onVPNPermissionRequestDialogDismissed() {
        isSwitchChecked.value = false
        vpnConnectionRequested.value = false
    }

    fun onVPNPermissionRequestDialogConfirmed() {
        vpnConnectionRequested.value = false
        makeVPNServiceRequest(VPN_SERVICE_ACTION.START_VPN)
    }


}