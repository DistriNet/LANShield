package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class VPNServiceWorker(private val appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    private fun hasVPNConsent(): Boolean {
        return VpnService.prepare(appContext) == null
    }

    override fun doWork(): Result {
        val context = applicationContext
        if(hasVPNConsent()) {
            val serviceIntent = Intent(context, VPNService::class.java)
            context.startForegroundService(serviceIntent)
            return Result.success()
        }
        return Result.failure()
    }

    companion object {
        /**
         * Starts the VPNService, does not require the caller to run in the foreground.
         */
        fun enqueueStartVpnService(context: Context) {
            val workRequest = OneTimeWorkRequest.Builder(VPNServiceWorker::class.java).build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}