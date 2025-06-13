package org.distrinet.lanshield.ui.settings

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.model.LanAccessPolicy
import org.distrinet.lanshield.getPackageMetadata
import java.util.SortedMap
import javax.inject.Inject


@HiltViewModel
class LANAccessPoliciesViewModel @Inject constructor(val lanAccessPolicyDao: LanAccessPolicyDao) :
    ViewModel() {


    fun getLanAccessPolicies(
        showSystem: Boolean,
        context: Context
    ): LiveData<SortedMap<String, LanAccessPolicy>> {
        return if (showSystem) lanAccessPolicyDao.getAllLive()
            .map { lanAccessPolicy ->
                lanAccessPolicy.associateBy({
                    getPackageMetadata(
                        it.packageName,
                        context
                    ).packageLabel
                }, { it }).toSortedMap(String.CASE_INSENSITIVE_ORDER)
            }
        else lanAccessPolicyDao.getAllNoSystem()
            .map { lanAccessPolicy ->
                lanAccessPolicy.associateBy({
                    getPackageMetadata(
                        it.packageName,
                        context
                    ).packageLabel
                }, { it }).toSortedMap(String.CASE_INSENSITIVE_ORDER)
            }
    }

    fun updateLanAccessPolicy(lanAccessPolicy: LanAccessPolicy): Unit {
        CoroutineScope(Dispatchers.IO).launch {
            if (lanAccessPolicy.accessPolicy == Policy.DEFAULT) {
                lanAccessPolicyDao.delete(lanAccessPolicy)
            } else {
                lanAccessPolicyDao.insert(lanAccessPolicy)
            }
        }
    }
}