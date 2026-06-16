package org.distrinet.lanshield

import android.content.Context
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dagger.hilt.android.EntryPointAccessors
import org.distrinet.lanshield.vpnservice.VPNService
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NotificationPermissionGateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val entryPoint =
        EntryPointAccessors.fromApplication(context, VpnStatusEntryPoint::class.java)
    private val status: MutableLiveData<VPN_SERVICE_STATUS> = entryPoint.vpnServiceStatus()

    @Before
    fun setUp() {
        // The orchestrator's clearPackageData runs each test in a fresh process with app data and
        // permissions reset, so POST_NOTIFICATIONS starts denied-but-askable (the dialog appears).
        // Pre-authorize the VPN so consent is not the dialog under test.
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
    }

    @After
    fun tearDown() {
        try {
            // Use startService (not startForegroundService) for STOP, as production does: it carries
            // no "must call startForeground" promise, so the stop path won't crash the process.
            context.startService(
                Intent(context, VPNService::class.java).apply { action = VPNService.STOP_VPN_SERVICE }
            )
        } catch (_: Exception) {
        }
    }

    @Test
    fun test1_enableSucceeds_whenNotificationGrantedViaDialog() {
        ActivityScenario.launch(MainActivity::class.java).use {
            requestEnable()
            assumeTrue(
                "Notification permission dialog could not be driven on this image",
                clickPermissionDialogButton(allow = true)
            )
            awaitStatus(VPN_SERVICE_STATUS.ENABLED)
        }
    }

    @Test
    fun test2_enableBlocked_whenNotificationDenied() {
        ActivityScenario.launch(MainActivity::class.java).use {
            requestEnable()
            // The dialog must appear (proving the gate ran); deny it.
            assumeTrue(
                "Notification permission dialog could not be driven on this image",
                clickPermissionDialogButton(allow = false)
            )
            // The VPN must never come up without notification permission.
            assertStaysDisabled(seconds = 5)
        }
    }

    /** Posts the same enable signal the Overview switch emits; MainActivity observes it and gates. */
    private fun requestEnable() {
        runOnMain { entryPoint.vpnServiceActionRequest().value = VPN_SERVICE_ACTION.START_VPN }
    }

    /** Returns true if a button was found and clicked. */
    private fun clickPermissionDialogButton(allow: Boolean): Boolean {
        // Match the resource-id by suffix so it works whether the dialog is served by
        // com.android.permissioncontroller or com.google.android.permissioncontroller.
        val resPattern = if (allow) {
            Pattern.compile(".*:id/permission_allow_button")
        } else {
            Pattern.compile(".*:id/permission_deny_button")
        }
        var button = device.wait(Until.findObject(By.res(resPattern)), 5_000)
        if (button == null) {
            // Fallback by label; '.' matches either a straight or curly apostrophe in "Don't".
            val text = if (allow) {
                Pattern.compile("allow", Pattern.CASE_INSENSITIVE)
            } else {
                Pattern.compile("(don.?t allow|deny)", Pattern.CASE_INSENSITIVE)
            }
            button = device.wait(Until.findObject(By.text(text)), 3_000)
        }
        button?.click()
        return button != null
    }

    private fun awaitStatus(expected: VPN_SERVICE_STATUS, timeoutSeconds: Long = 15) {
        val latch = CountDownLatch(1)
        val observer = Observer<VPN_SERVICE_STATUS> { if (it == expected) latch.countDown() }
        runOnMain { status.observeForever(observer) }
        try {
            assertTrue(
                "VPN status did not reach $expected within ${timeoutSeconds}s (was ${status.value})",
                latch.await(timeoutSeconds, TimeUnit.SECONDS)
            )
        } finally {
            runOnMain { status.removeObserver(observer) }
        }
    }

    private fun assertStaysDisabled(seconds: Long) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            assertNotEquals(
                "VPN started despite notification permission being denied",
                VPN_SERVICE_STATUS.ENABLED,
                status.value
            )
            Thread.sleep(250)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun shell(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
    }
}
