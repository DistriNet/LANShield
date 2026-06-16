package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.VpnStatusEntryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VpnServiceStartCommandTest {

    @Test
    fun endToEnd_restartReEstablishesVpn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantVpnConsent(context.packageName)

        // If consent could not be granted on this image, there is nothing meaningful to assert.
        assumeTrue("VPN consent unavailable on this device/image", VpnService.prepare(context) == null)

        val status = EntryPointAccessors
            .fromApplication(context, VpnStatusEntryPoint::class.java)
            .vpnServiceStatus()

        try {
            // 1. A fresh start (no action) must bring the VPN up.
            context.startForegroundService(Intent(context, VPNService::class.java))
            awaitStatus(status, VPN_SERVICE_STATUS.ENABLED)

            // 2. Simulate the OS restarting the still-running service with a bare, action-less
            //    intent (closest a test can get to the system's null re-delivery). It must remain
            //    ENABLED rather than being torn down.
            context.startForegroundService(Intent(context, VPNService::class.java))
            Thread.sleep(500)
            assertEquals(VPN_SERVICE_STATUS.ENABLED, status.value)

            // 3. An explicit stop must actually stop it (and stopSelf so it is not resurrected).
            //    Use startService for STOP, as production does: startForegroundService would create a
            //    "must call startForeground" promise that the stop path never fulfills (it stops),
            //    crashing the process.
            context.startService(
                Intent(context, VPNService::class.java).apply { action = VPNService.STOP_VPN_SERVICE }
            )
            awaitStatus(status, VPN_SERVICE_STATUS.DISABLED)
        } finally {
            context.startService(
                Intent(context, VPNService::class.java).apply { action = VPNService.STOP_VPN_SERVICE }
            )
        }
    }

    /** Blocks until [status] reaches [expected], asserting it does so within the timeout. */
    private fun awaitStatus(
        status: MutableLiveData<VPN_SERVICE_STATUS>,
        expected: VPN_SERVICE_STATUS,
        timeoutSeconds: Long = 10
    ) {
        val latch = CountDownLatch(1)
        val observer = object : Observer<VPN_SERVICE_STATUS> {
            override fun onChanged(value: VPN_SERVICE_STATUS) {
                if (value == expected) latch.countDown()
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { status.observeForever(observer) }
        try {
            assertTrue(
                "VPN status did not reach $expected within ${timeoutSeconds}s (was ${status.value})",
                latch.await(timeoutSeconds, TimeUnit.SECONDS)
            )
        } finally {
            instrumentation.runOnMainSync { status.removeObserver(observer) }
        }
    }

    private fun grantVpnConsent(packageName: String) {
        executeShellCommand("appops set $packageName ACTIVATE_VPN allow")
    }

    private fun executeShellCommand(command: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = automation.executeShellCommand(command)
        // Drain so the command completes before we return.
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
    }
}
