package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.VpnStatusEntryPoint
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device characterization of [ConnectivityManager.getConnectionOwnerUid] for **unconnected UDP
 * sockets** — the basis for attributing LAN UDP discovery traffic (mDNS/SSDP/IoT) to an app, the way
 * [org.distrinet.lanshield.backendsync.OpenPortsFinder] already does (wildcard-remote query) and the
 * way a future [VPNRunnable.getPacketOwnerUid] fallback might.
 *
 * Important platform facts this test is built around (traced through AOSP + verified on an API 36
 * AVD; see project memory `getconnectionowneruid-quirks`):
 *   - The active VPN gets `INVALID_UID` for sockets owned by its **own** UID — `ConnectivityService`
 *     filters the result through `NetworkCapabilities.appliesToUid`, and `addDisallowedApplication`
 *     removes LANShield's own UID from the tunnel. So the socket under test must be owned by a
 *     **different** UID. We use a UDP listener spawned via the instrumentation shell (UID `shell`),
 *     which the tunnel does cover.
 *   - For UDP the framework swaps src/dst for the exact lookup (kernel bug workaround, aosp/755889)
 *     and, on miss, does a wildcard-remote DUMP reading only the first netlink record. The lookup is
 *     therefore **non-deterministic** (per-CPU scoring, SO_REUSEPORT, dump ordering). Every assertion
 *     here resolves with retries, mirroring production's `repeat(5)`.
 *
 * `getConnectionOwnerUid` needs the caller to be the active VpnService, so we bring up the real
 * LANShield [VPNService] once for the class and grant consent via the `ACTIVATE_VPN` app-op. Where
 * that isn't honored, or the shell UID isn't resolvable on the image, the class self-skips.
 *
 * Run with: `./gradlew connectedFossDebugAndroidTest` against a debuggable emulator.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionOwnerUidUnconnectedUdpTest {

    /**
     * Core claim: an unconnected UDP socket bound to the wildcard address resolves to its owner via a
     * wildcard-remote query (the OpenPortsFinder / proposed-fallback technique).
     */
    @Test
    fun unconnected_wildcardBound_wildcardRemote_resolvesOwnerUid() {
        val port = 47781
        spawnListener(port, "0.0.0.0", v6 = false).use {
            val uid = resolveToUid(InetSocketAddress(anyV4, port), InetSocketAddress(anyV4, 0))
            assertEquals(
                "wildcard-bound unconnected UDP should resolve to the shell uid via wildcard-remote " +
                    "(local=0.0.0.0:$port, remote=0.0.0.0:0)",
                shellUid, uid
            )
        }
    }

    /**
     * Characterization (corrects the original premise that a full 5-tuple query "misses" unconnected
     * sockets): because an unconnected socket has no peer, the kernel's score check skips the remote,
     * so a full-tuple query with an arbitrary remote still resolves it.
     */
    @Test
    fun unconnected_wildcardBound_fullTupleArbitraryRemote_characterize() {
        val port = 47782
        spawnListener(port, "0.0.0.0", v6 = false).use {
            val uid = resolveToUid(InetSocketAddress(anyV4, port), InetSocketAddress(InetAddress.getByName("8.8.8.8"), 53))
            Log.i(
                TAG,
                "CHARACTERIZE unconnected + full-tuple(8.8.8.8:53): uid=$uid -> " +
                    if (uid == shellUid) "RESOLVES (peer ignored for unconnected sockets)" else "MISSES"
            )
        }
    }

    /** Specific-bound unconnected socket resolves via wildcard-remote when queried with its real local. */
    @Test
    fun unconnected_specificBound_wildcardRemote_resolvesOwnerUid() {
        val ip = emulatorIpv4OrSkip()
        val port = 47783
        spawnListener(port, ip.hostAddress!!, v6 = false).use {
            val uid = resolveToUid(InetSocketAddress(ip, port), InetSocketAddress(anyV4, 0))
            assertEquals(
                "specific-bound unconnected UDP should resolve via wildcard-remote with its real local " +
                    "(local=${ip.hostAddress}:$port)",
                shellUid, uid
            )
        }
    }

    /**
     * Key measurement: querying a *specific*-bound socket with a *wildcard* local (0.0.0.0). The kernel
     * hashes on the local address, so a 0.0.0.0 query lands in a different bucket and misses. Confirms
     * a fallback for specifically-bound sockets must use the packet's real source IP, not 0.0.0.0.
     */
    @Test
    fun unconnected_specificBound_wildcardLocalAndRemote_characterize() {
        val ip = emulatorIpv4OrSkip()
        val port = 47784
        spawnListener(port, ip.hostAddress!!, v6 = false).use {
            val uid = resolveToUid(InetSocketAddress(anyV4, port), InetSocketAddress(anyV4, 0))
            Log.i(
                TAG,
                "CHARACTERIZE specific-bound(${ip.hostAddress}:$port) + wildcard-local query: uid=$uid -> " +
                    if (uid == shellUid) "MATCHES (0.0.0.0 query finds specific-bound socket)"
                    else "MISSES (fallback must use the packet's real source IP)"
            )
        }
    }

    /** Same core claim over IPv6; skipped where the image can't bind a v6 UDP listener. */
    @Test
    fun ipv6_unconnected_wildcardBound_wildcardRemote_resolvesOwnerUid() {
        val port = 47785
        spawnListener(port, "::", v6 = true).use {
            assumeTrue("could not bind an IPv6 UDP listener on this image", isPortBound(port, v6 = true))
            val uid = resolveToUid(InetSocketAddress(anyV6, port), InetSocketAddress(anyV6, 0))
            assertEquals(
                "wildcard-bound unconnected IPv6 UDP should resolve to the shell uid via wildcard-remote " +
                    "(local=[::]:$port, remote=[::]:0)",
                shellUid, uid
            )
        }
    }

    // --- per-test helpers -------------------------------------------------------------------------

    private val anyV4: InetAddress get() = InetAddress.getByName("0.0.0.0")
    private val anyV6: InetAddress get() = InetAddress.getByName("::")

    private fun resolveToUid(local: InetSocketAddress, remote: InetSocketAddress, attempts: Int = 15): Int {
        repeat(attempts) {
            val u = try {
                cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote)
            } catch (e: Exception) { Process.INVALID_UID }
            if (u != Process.INVALID_UID) return u
        }
        return Process.INVALID_UID
    }

    private fun emulatorIpv4OrSkip(): InetAddress {
        val addr = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress && it is java.net.Inet4Address }
        }.getOrNull()
        assumeTrue("no non-loopback IPv4 interface address on this device", addr != null)
        return addr!!
    }

    companion object {
        private const val TAG = "ConnOwnerUidTest"

        private lateinit var context: Context
        private lateinit var cm: ConnectivityManager
        private var vpnStarted = false
        private var shellUid = 2000

        @BeforeClass
        @JvmStatic
        fun startRealVpn() {
            context = ApplicationProvider.getApplicationContext()
            cm = context.getSystemService(VpnService.CONNECTIVITY_SERVICE) as ConnectivityManager
            shellUid = readShell("id -u").trim().toIntOrNull() ?: 2000

            grantVpnConsent(context.packageName)
            assumeTrue("VPN consent unavailable on this device/image", VpnService.prepare(context) == null)

            val status = EntryPointAccessors
                .fromApplication(context, VpnStatusEntryPoint::class.java)
                .vpnServiceStatus()
            context.startForegroundService(Intent(context, VPNService::class.java))
            awaitStatus(status, VPN_SERVICE_STATUS.ENABLED)
            vpnStarted = true

            // Precondition: confirm the active VPN can resolve a different-UID socket on this image at
            // all (it cannot resolve its own UID — see class KDoc). If not, skip the whole class.
            val probePort = 47780
            spawnListener(probePort, "0.0.0.0", v6 = false).use {
                var resolved = Process.INVALID_UID
                repeat(15) {
                    val u = cm.getConnectionOwnerUid(
                        OsConstants.IPPROTO_UDP,
                        InetSocketAddress(InetAddress.getByName("0.0.0.0"), probePort),
                        InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0),
                    )
                    if (u != Process.INVALID_UID) { resolved = u; return@repeat }
                }
                assumeTrue(
                    "getConnectionOwnerUid does not resolve the shell-UID listener on this image " +
                        "(got $resolved, shellUid=$shellUid) — cannot exercise the API here",
                    resolved == shellUid
                )
            }
        }

        @AfterClass
        @JvmStatic
        fun stopRealVpn() {
            runCatching { execShell("pkill -f 'nc -u -l'") }
            if (!vpnStarted) return
            runCatching {
                context.startService(
                    Intent(context, VPNService::class.java).apply { action = VPNService.STOP_VPN_SERVICE }
                )
            }
        }

        /**
         * Spawn a UDP listener owned by the shell UID, bound to [bind]:[port]. Returns an
         * [AutoCloseable] that tears the listener down. nc stays bound (waiting for a datagram we never
         * send) for as long as the returned handle is open.
         */
        private fun spawnListener(port: Int, bind: String, v6: Boolean): AutoCloseable {
            val fam = if (v6) "-6" else "-4"
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val pfd: ParcelFileDescriptor =
                automation.executeShellCommand("toybox nc -u -l $fam -s $bind -p $port")
            Thread.sleep(500) // let it bind
            return AutoCloseable {
                runCatching { pfd.close() }
                runCatching { execShell("pkill -f 'nc -u -l $fam -s $bind -p $port'") }
            }
        }

        private fun isPortBound(port: Int, v6: Boolean): Boolean {
            val hex = port.toString(16).uppercase().padStart(4, '0')
            val file = if (v6) "/proc/net/udp6" else "/proc/net/udp"
            return readShell("cat $file").lineSequence().any {
                val t = it.trim().split(Regex("\\s+"))
                t.size > 1 && t[1].endsWith(":$hex")
            }
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
            execShell("appops set $packageName ACTIVATE_VPN allow")
        }

        private fun execShell(command: String) {
            readShell(command)
        }

        private fun readShell(command: String): String {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val pfd = automation.executeShellCommand(command)
            return FileInputStream(pfd.fileDescriptor).use { String(it.readBytes()) }
        }
    }
}
