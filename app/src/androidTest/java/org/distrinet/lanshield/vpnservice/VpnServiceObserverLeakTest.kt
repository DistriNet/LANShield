package org.distrinet.lanshield.vpnservice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.VpnStatusEntryPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * EXPOSES BUG (Finding 2): [VPNService.startVPNThread] registers seven `observeForever` observers but
 * [VPNService.stopVPNThread] removes only three. The four it forgets — `allowMulticastLive`,
 * `allowDnsLive`, `hideMulticastNotificationsLive`, `hideDnsNotificationsLive` — stay attached after
 * the VPN stops, leaking the old [VPNRunnable] on every stop/restart cycle.
 *
 * The test drives the real service (start → stop) on a device/emulator, grabbing the running
 * [VPNService] instance from `ActivityThread.mServices` so it can inspect the private LiveData fields.
 * After the stop, every observer added at start should be gone; the four leaked sources still report
 * observers, so `stop removes every observer it added` FAILS until the leak is fixed.
 *
 * Like [VpnServiceStartCommandTest], it self-skips where VPN consent cannot be granted, keeping
 * consent automation out of mandatory CI.
 */
@RunWith(AndroidJUnit4::class)
class VpnServiceObserverLeakTest {

    /** All seven LiveData fields touched by start; stop must clear observers from every one. */
    private val observedFields = listOf(
        // Correctly removed today (control — these should stay green).
        "accessPolicies",
        "defaultForwardPolicyLive",
        "systemAppsForwardPolicyLive",
        // Leaked today (these expose the bug).
        "allowMulticastLive",
        "allowDnsLive",
        "hideMulticastNotificationsLive",
        "hideDnsNotificationsLive",
    )

    @Test
    fun `stop removes every observer it added`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantVpnConsent(context.packageName)
        assumeTrue("VPN consent unavailable on this device/image", VpnService.prepare(context) == null)

        val status = EntryPointAccessors
            .fromApplication(context, VpnStatusEntryPoint::class.java)
            .vpnServiceStatus()

        var service: VPNService? = null
        try {
            // 1. Start the VPN and wait until it is up, then capture the live service instance.
            context.startForegroundService(Intent(context, VPNService::class.java))
            awaitStatus(status, VPN_SERVICE_STATUS.ENABLED)
            service = runOnMain { findRunningVpnService() }
            assumeTrue("Could not locate the running VPNService instance", service != null)

            // 2. Stop it. stopVPNThread() should now remove every observer it registered at start.
            context.startService(
                Intent(context, VPNService::class.java).apply { action = VPNService.STOP_VPN_SERVICE }
            )
            awaitStatus(status, VPN_SERVICE_STATUS.DISABLED)

            // 3. No LiveData touched at start may still have observers after the stop.
            val stillObserved = runOnMain {
                observedFields.filter { liveDataField(service!!, it).hasObservers() }
            }
            assertFalse(
                "VPNService leaked observers after stop on: $stillObserved " +
                    "(stopVPNThread removes only 3 of the 7 observers added by startVPNThread)",
                stillObserved.isNotEmpty()
            )
        } finally {
            context.startService(
                Intent(context, VPNService::class.java).apply { action = VPNService.STOP_VPN_SERVICE }
            )
        }
    }

    private fun liveDataField(service: VPNService, name: String): LiveData<*> {
        val field = VPNService::class.java.getDeclaredField(name).apply { isAccessible = true }
        return field.get(service) as LiveData<*>
    }

    /** Find the VPNService instance the framework is currently running, in this process. */
    private fun findRunningVpnService(): VPNService? {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        val servicesField = activityThreadClass.getDeclaredField("mServices").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val services = servicesField.get(activityThread) as Map<*, Service>
        return services.values.filterIsInstance<VPNService>().firstOrNull()
    }

    private fun <T> runOnMain(block: () -> T): T {
        var result: T? = null
        var error: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun awaitStatus(
        status: MutableLiveData<VPN_SERVICE_STATUS>,
        expected: VPN_SERVICE_STATUS,
        timeoutSeconds: Long = 10
    ) {
        val latch = CountDownLatch(1)
        val observer = Observer<VPN_SERVICE_STATUS> { if (it == expected) latch.countDown() }
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
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
    }
}
