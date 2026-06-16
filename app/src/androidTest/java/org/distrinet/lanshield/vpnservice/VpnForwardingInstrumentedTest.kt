package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

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
