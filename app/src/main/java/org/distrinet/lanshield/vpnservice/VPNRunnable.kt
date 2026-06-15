package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants.IPPROTO_TCP
import android.system.OsConstants.IPPROTO_UDP
import android.util.Log
import android.util.SparseArray
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import org.distrinet.lanshield.PACKAGE_NAME_UNKNOWN
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.Policy.ALLOW
import org.distrinet.lanshield.Policy.BLOCK
import org.distrinet.lanshield.Policy.DEFAULT
import org.distrinet.lanshield.TAG
import org.distrinet.lanshield.crashreport.crashReporter
import org.distrinet.lanshield.database.AppDatabase
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.database.model.LanAccessPolicy
import org.distrinet.lanshield.getPackageMetadata
import org.distrinet.lanshield.getPackageNameFromUid
import tech.httptoolkit.android.vpn.ClientPacketWriter
import tech.httptoolkit.android.vpn.SessionHandler
import tech.httptoolkit.android.vpn.SessionManager
import tech.httptoolkit.android.vpn.socket.SocketNIODataService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.nio.ByteBuffer

// Set on our VPN as the MTU, which should guarantee all packets fit this
const val MAX_PACKET_LEN = 1500

class VPNRunnable(
    vpnInterface: ParcelFileDescriptor,
    private val vpnNotificationManager: LANShieldNotificationManager,
    private val context: Context
) : Runnable {

    companion object {
        init {
            try {
                System.loadLibrary("lanshield-dpi")
            } catch (e: Throwable) {
                // If the native DPI library is unavailable, continue with DPI disabled
                // rather than crashing the VPN service at class-load time. doDpi() handles
                // the resulting UnsatisfiedLinkError on each call and returns null.
                Log.e(TAG, "Failed to load lanshield-dpi native library: ${e.message}")
                crashReporter.recordException(e)
            }
        }

        private val dpiLock = Any()

        private external fun _doDPI(
            packet: ByteArray,
            packetSize: Int,
            packetOffset: Int,
            dpiResult: DpiResult
        ): Int

        external fun terminateNDPI()

        fun doDpi(packet: ByteArray, packetSize: Int, packetOffset: Int): DpiResult? {
            val dpiResult = DpiResult()
            return synchronized(dpiLock) {
                try {
                    val res = _doDPI(packet, packetSize, packetOffset, dpiResult)
                    if (res == 0) {
                        Log.d(TAG, "Dpi result: $dpiResult")
                        dpiResult
                    } else {
                        null
                    }
                } catch (e: Throwable) {
                    // Catch Throwable (not just Exception) so a native failure such as an
                    // UnsatisfiedLinkError degrades DPI gracefully instead of propagating up
                    // and tearing down the whole VPN packet loop.
                    Log.e(TAG, "Error during DPI: ${e.message}")
                    null
                }
            }
        }
    }


    private val connectivityManager =
        context.getSystemService(VpnService.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var threadMainLoopActive = false

    private val appDatabase = AppDatabase.getDatabase(context)

    private val vpnReadStream = FileInputStream(vpnInterface.fileDescriptor)
    private val vpnWriteStream = FileOutputStream(vpnInterface.fileDescriptor)

    private val vpnPacketWriterRunnable = ClientPacketWriter(vpnWriteStream)
    private val vpnPacketWriterThread = Thread(vpnPacketWriterRunnable)

    private val nioServiceRunnable = SocketNIODataService(vpnPacketWriterRunnable, appDatabase)
    private val dataServiceThread = Thread(nioServiceRunnable, "Socket NIO thread")

    private val httpToolkitSessionManager = SessionManager(appDatabase)
    private val httpToolkitSessionHandler =
        SessionHandler(
            httpToolkitSessionManager,
            nioServiceRunnable,
            vpnPacketWriterRunnable,
            appDatabase
        )

    // Allocate the buffer for a single packet.
    private val packetBuffer = ByteBuffer.allocate(MAX_PACKET_LEN)


    @Volatile
    private var defaultForwardPolicy = ALLOW

    @Volatile
    private var systemAppsForwardPolicy = ALLOW

    @Volatile
    private var allowMulticast = false

    @Volatile
    private var allowDns = false

    @Volatile
    private var hideMulticastNotifications = false

    @Volatile
    private var hideDnsNotifications = false

    @Synchronized
    fun setDefaultForwardPolicy(policy: Policy) {
        defaultForwardPolicy = policy
    }

    @Synchronized
    fun setSystemAppsForwardPolicy(policy: Policy) {
        systemAppsForwardPolicy = policy
    }

    @Volatile
    private var accessPoliciesCache = HashMap<String, Policy>()

    @Synchronized
    private fun updateAccessPoliciesCache(newCache: HashMap<String, Policy>) {
        accessPoliciesCache = newCache
    }

    var accessPoliesObserver =
        Observer<List<LanAccessPolicy>> { policies ->
            val newCache = HashMap<String, Policy>()
            policies.forEach {
                newCache[it.packageName] = it.accessPolicy
            }
            updateAccessPoliciesCache(newCache)
        }

    var defaultPolicyObserver = Observer<Policy> { setDefaultForwardPolicy(it) }
    var systemAppsPolicyObserver = Observer<Policy> { setSystemAppsForwardPolicy(it) }
    var allowMulticastObserver = Observer<Boolean> { allowMulticast = it }
    var allowDnsObserver = Observer<Boolean> { allowDns = it }
    var hideMulticastNotificationsObserver = Observer<Boolean> { hideMulticastNotifications = it }
    var hideDnsNotificationsObserver = Observer<Boolean> { hideDnsNotifications = it }


    private fun logBlockedPacket(
        packetHeader: IPHeader,
        rawPacket: ByteBuffer,
        packageName: String
    ) {
        val lanFlow = LANFlow.createFlow(
            appId = packageName,
            remoteEndpoint = packetHeader.destination,
            localEndpoint = packetHeader.source,
            transportLayerProtocol = packetHeader.protocolNumberAsString(),
            appliedPolicy = BLOCK
        )
        lanFlow.dataEgress = packetHeader.size.toLong()
        lanFlow.packetCountEgress = 1
        val dpiResult = getDpiResult(packetHeader, rawPacket)
        if (dpiResult != null) {
            lanFlow.dpiReport = dpiResult.jsonBuffer
            lanFlow.dpiProtocol = dpiResult.protocolName
        }
        CoroutineScope(Dispatchers.IO).launch {
            appDatabase.FlowDao().insertFlow(lanFlow)
        }
    }


    override fun run() {
        if (threadMainLoopActive) {
            Log.w(TAG, "Vpn runnable started, but it's already running")
            return
        }

        Log.i(TAG, "Vpn thread starting")
        httpToolkitSessionManager.setTcpPortRedirections(SparseArray())
        dataServiceThread.priority = Thread.NORM_PRIORITY
        vpnPacketWriterThread.priority = Thread.NORM_PRIORITY
        dataServiceThread.start()
        vpnPacketWriterThread.start()

        var packetLength: Int

        threadMainLoopActive = true
        val packetBufferArray: ByteArray
        try {
            packetBufferArray = packetBuffer.array()
        } catch (_: Exception) {
            Log.wtf(TAG, "packetBuffer not backed by array")
            threadMainLoopActive = false
            return
        }
        while (threadMainLoopActive) {
            try {
                packetBuffer.clear()
                packetLength = vpnReadStream.read(
                    packetBufferArray,
                    packetBuffer.arrayOffset(),
                    MAX_PACKET_LEN
                )

                if (packetLength > 0) {
                    try {
                        packetBuffer.limit(packetLength)
                        val packetHeader = IPHeader(packetBuffer)
                        val (shouldForward, packageName) = shouldForwardPacket(packetHeader)

                        packetBuffer.rewind()
                        if (shouldForward) {
                            httpToolkitSessionHandler.handlePacket(packetBuffer, packageName)
                        } else {
                            logBlockedPacket(packetHeader, packetBuffer, packageName)
                        }

                    } catch (e: Exception) {
                        val errorMessage = (e.message ?: e.toString())
                        Log.e(TAG, errorMessage)

                        val isIgnorable =
                            (e is ConnectException && errorMessage == "Permission denied") ||
                                    // Nothing we can do if the internet goes down:
                                    (e is ConnectException && errorMessage == "Network is unreachable") ||
                                    (e is ConnectException && errorMessage.contains("ENETUNREACH")) ||
                                    // Too many open files - can't make more sockets, not much we can do:
                                    (e is ConnectException && errorMessage == "Too many open files") ||
                                    (e is ConnectException && errorMessage.contains("EMFILE"))

                        if (!isIgnorable) {
                            Log.e(TAG, e.toString())
                        }
                    }
                } else if (packetLength == 0) {
                    Thread.sleep(10)
                    Log.wtf(TAG, "vpnReadStream not configured as blocking!")
                } else {
                    threadMainLoopActive = false
                    Log.e(TAG, "TUN socket closed unexpected")
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Sleep interrupted: " + e.message)
            } catch (e: InterruptedIOException) {
                Log.i(TAG, "Read interrupted: " + e.message)
            } catch (e: IOException) {
                Log.i(TAG, "IO Interrupted: " + e.message)
                stop()
            }
        }
        Log.d(TAG, "Vpn thread shutting down")
    }

    private fun getDpiResult(
        packetHeader: IPHeader,
        packetRaw: ByteBuffer,
    ): DpiResult? {
        if (packetHeader.protocolNumberAsOSConstant() == IPPROTO_UDP
            || (packetHeader.protocolNumberAsOSConstant() == IPPROTO_TCP && packetHeader.hasPayloadForDpi())
        ) {
            return doDpi(packetRaw.array(), packetRaw.limit(), packetRaw.arrayOffset())
        }
        return null
    }

    private fun getPacketOwnerUid(pkt: IPHeader): Int {
        repeat(5) {
            try {
                val uid = connectivityManager.getConnectionOwnerUid(
                    pkt.protocolNumberAsOSConstant(),
                    pkt.source,
                    pkt.destination
                )
                if (uid != 0 && uid != -1) {
                    return uid
                }

            } catch (e: IllegalArgumentException) {
                return -1
            }
        }
        return -1
    }

    private fun shouldForwardPacket(packetHeader: IPHeader): Pair<Boolean, String> {

        val transportLayerProtocol = packetHeader.protocolNumberAsOSConstant()
        val isTcpOrUdp = transportLayerProtocol == IPPROTO_TCP
                || transportLayerProtocol == IPPROTO_UDP

        // We can only look up the app's uid for TCP and UDP packets.
        var appPackageName = PACKAGE_NAME_UNKNOWN
        var perAppPolicy = DEFAULT
        var isSystemApp = false
        var hasValidUid = false
        if (isTcpOrUdp) {
            val appUid = getPacketOwnerUid(packetHeader)
            hasValidUid = appUid != -1 && appUid != 1000 && appUid != 0
            if (hasValidUid) {
                appPackageName = getPackageNameFromUid(appUid, context.packageManager)
                perAppPolicy = accessPoliciesCache.getOrDefault(appPackageName, DEFAULT)
                isSystemApp = getPackageMetadata(appPackageName, context.packageManager).isSystem
            }
        }

        val decision = PolicyEngine.decide(
            PacketDecisionInput(
                isTcpOrUdp = isTcpOrUdp,
                hasValidUid = hasValidUid,
                destAddress = packetHeader.destination.address,
                destPort = packetHeader.destination.port,
                perAppPolicy = perAppPolicy,
                isSystemApp = isSystemApp,
                defaultForwardPolicy = defaultForwardPolicy,
                systemAppsForwardPolicy = systemAppsForwardPolicy,
                allowMulticast = allowMulticast,
                allowDns = allowDns,
                hideDnsNotifications = hideDnsNotifications,
                hideMulticastNotifications = hideMulticastNotifications,
            )
        )

        if (decision.shouldNotify) {
            vpnNotificationManager.postNotification(
                packageName = appPackageName,
                decision.appliedPolicy,
                packetHeader.destination
            )
        }
        return Pair(decision.shouldForward, appPackageName)
    }

    fun stop() {
        if (threadMainLoopActive) {
            threadMainLoopActive = false
            nioServiceRunnable.shutdown()
            dataServiceThread.interrupt()

            vpnPacketWriterRunnable.shutdown()
            vpnPacketWriterThread.interrupt()
        } else {
            Log.w(TAG, "Vpn runnable stopped, but it's not running")
        }
    }
}

