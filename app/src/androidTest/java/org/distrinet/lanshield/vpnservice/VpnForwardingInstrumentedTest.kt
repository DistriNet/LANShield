package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, on-device end-to-end test of the real VpnService path. This is NOT part of CI:
 * establishing a VPN requires the system consent dialog (returned by [VpnService.prepare]),
 * which cannot be granted programmatically. The deterministic forward/receive behaviour is
 * already covered locally by UdpForwardingTest / TcpForwardingTest against loopback sockets.
 *
 * To run manually on a connected device/emulator (and grant the consent dialog by hand, or
 * drive it with UiAutomator):
 *
 *   ./gradlew connectedFossDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.notAnnotation=org.junit.Ignore
 *
 * A full implementation would: call VpnService.prepare(); accept the consent intent (manual
 * or UiAutomator By.text("OK")/Allow — locale-fragile); start LANShield's VpnService; drive
 * an outbound connection to a known LAN/loopback peer; and assert a LANFlow row is recorded
 * in the database. Kept as a documented skeleton so it stays compilable without pulling in
 * UiAutomator or wiring CI for VPN consent.
 */
@RunWith(AndroidJUnit4::class)
class VpnForwardingInstrumentedTest {

    @Test
    @Ignore("Manual only: requires interactive VPN consent; not runnable in CI")
    fun realVpnRecordsAnOutboundFlow() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // prepare() returns a consent Intent the first time, or null once already authorized.
        // Just exercising the call here; a full run would grant consent and drive a flow.
        VpnService.prepare(context)
        // TODO(manual): grant consent, start the VpnService, drive a flow, assert it is recorded.
    }
}
