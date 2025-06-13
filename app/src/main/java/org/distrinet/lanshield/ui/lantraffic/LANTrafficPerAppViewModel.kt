package org.distrinet.lanshield.ui.lantraffic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.distrinet.lanshield.DEFAULT_POLICY_KEY
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.database.dao.FlowDao
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.database.model.LanAccessPolicy
import javax.inject.Inject

@HiltViewModel
class LANTrafficPerAppViewModel @Inject constructor(val flowDao: FlowDao, val lanAccessPolicyDao: LanAccessPolicyDao, val dataStore: DataStore<Preferences>) : ViewModel() {

    val defaultPolicy =
        dataStore.data.map { Policy.valueOf(it[DEFAULT_POLICY_KEY] ?: Policy.DEFAULT.toString()) }
            .distinctUntilChanged()
    fun getAccessPolicy(packageName: String): LiveData<Policy?> {
        return lanAccessPolicyDao.getPolicyByPackageName(packageName)
    }

    suspend fun onChangeAccessPolicy(packageName: String, newPolicy: Policy, isSystemApp: Boolean) {
        withContext(Dispatchers.IO) {
            val lanAccessPolicy = LanAccessPolicy(packageName, newPolicy, isSystemApp)

            if(newPolicy == Policy.DEFAULT) {
                lanAccessPolicyDao.delete(lanAccessPolicy)
            }
            else {
                lanAccessPolicyDao.insert(lanAccessPolicy)
            }

        }
    }

    fun getLANFlows(packageName: String): Flow<List<LANFlow>> {
        return flowDao.getFlowsByAppId(packageName)
    }

    fun clearLANFlows(packageName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            flowDao.deleteFlowsWithAppId(packageName)
        }
    }
}