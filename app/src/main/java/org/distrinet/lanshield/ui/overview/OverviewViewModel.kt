package org.distrinet.lanshield.ui.overview


import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.distrinet.lanshield.DEFAULT_POLICY_KEY
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.VPN_ALWAYS_ON_STATUS
import org.distrinet.lanshield.VPN_SERVICE_ACTION
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import javax.inject.Inject

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val vpnServiceActionRequest: MutableLiveData<VPN_SERVICE_ACTION>,
    vpnServiceStatus: MutableLiveData<VPN_SERVICE_STATUS>,
    vpnServiceAlwaysOnStatus: MutableLiveData<VPN_ALWAYS_ON_STATUS>,
    dataStore: DataStore<Preferences>,
    lanAccessPolicyDao: LanAccessPolicyDao,
) : ViewModel() {

    val defaultPolicy =
        dataStore.data.map { Policy.valueOf(it[DEFAULT_POLICY_KEY] ?: Policy.DEFAULT.toString()) }
            .distinctUntilChanged()
    val amountAllowedApps = lanAccessPolicyDao.countByPolicy(Policy.ALLOW)
    val amountBlockedApps = lanAccessPolicyDao.countByPolicy(Policy.BLOCK)


    private fun makeVPNServiceRequest(action: VPN_SERVICE_ACTION) {
        vpnServiceActionRequest.postValue(action)
    }

    val vpnServiceStatus: LiveData<VPN_SERVICE_STATUS> = vpnServiceStatus
    val vpnServiceAlwaysOnStatus: LiveData<VPN_ALWAYS_ON_STATUS> = vpnServiceAlwaysOnStatus

    private val isSwitchChecked = MutableStateFlow(false)
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