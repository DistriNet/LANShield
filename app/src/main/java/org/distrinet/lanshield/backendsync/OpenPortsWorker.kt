package org.distrinet.lanshield.backendsync

import android.content.Context
import android.net.ConnectivityManager
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.MutableLiveData
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.database.dao.OpenPortsDao

@HiltWorker
class OpenPortsWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Assisted private val openPortsDao: OpenPortsDao,
    @Assisted private val vpnServiceStatus: MutableLiveData<VPN_SERVICE_STATUS>
    ) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {

        if(vpnServiceStatus.value != VPN_SERVICE_STATUS.ENABLED) return Result.success()

        val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
        val packageManager = applicationContext.packageManager

        val openPorts = findOpenPorts(packageManager, connectivityManager)
        openPortsDao.insertAll(openPorts)

        return Result.success()
    }
}
