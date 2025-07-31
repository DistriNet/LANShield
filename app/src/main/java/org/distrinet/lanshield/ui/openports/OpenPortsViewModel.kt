package org.distrinet.lanshield.ui.openports

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.backendsync.findOpenPorts
import org.distrinet.lanshield.database.dao.OpenPortsDao
import org.distrinet.lanshield.database.model.OpenPorts
import javax.inject.Inject


@HiltViewModel
class OpenPortsViewModel @Inject constructor(
    vpnServiceStatus: MutableLiveData<VPN_SERVICE_STATUS>,
    val openPortsDao: OpenPortsDao
) : ViewModel() {

    val vpnServiceStatus: LiveData<VPN_SERVICE_STATUS> = vpnServiceStatus

    private val _appsWithPorts = MutableStateFlow<List<OpenPorts>>(emptyList())
    val appsWithPorts: StateFlow<List<OpenPorts>> = _appsWithPorts

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing


    fun refreshOpenPorts(context: Context) {
        _isRefreshing.value = true
        viewModelScope.launch(Dispatchers.Default) {
            val openPorts = findOpenPorts(
                pm = context.packageManager,
                connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            )

            openPortsDao.insertAll(openPorts)
            _appsWithPorts.value = openPorts
            _isRefreshing.value = false
        }
    }

}
