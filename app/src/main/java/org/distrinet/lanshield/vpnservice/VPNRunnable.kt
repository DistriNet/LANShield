package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process.INVALID_UID
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
import org.distrinet.lanshield.database.AppDatabase
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.database.model.LanAccessPolicy
import org.distrinet.lanshield.getPackageMetadata
import org.distrinet.lanshield.getPackageNameFromUid
import org.distrinet.lanshield.pna.PnaManager
import tech.httptoolkit.android.vpn.ClientPacketWriter
import tech.httptoolkit.android.vpn.SessionHandler
import tech.httptoolkit.android.vpn.SessionManager
import tech.httptoolkit.android.vpn.socket.SocketNIODataService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
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
            System.loadLibrary("lanshield-dpi")
        }

        private val dpiLock = Any()

        private external fun _doDPI(packet: ByteArray, packetSize: Int, packetOffset : Int, dpiResult: DpiResult): Int

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
                } catch (e: Exception) {
                    Log.e(TAG, "Error during DPI: ${e.message}")
                    null
                }
            }
        }
    }


    private val connectivityManager = context.getSystemService(VpnService.CONNECTIVITY_SERVICE) as ConnectivityManager

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
        SessionHandler(httpToolkitSessionManager, nioServiceRunnable, vpnPacketWriterRunnable, appDatabase)

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
    private var hideMulticastNot = false

    @Volatile
    private var hideDnsNot = false

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
    var hideMulticastNotObserver = Observer<Boolean> { hideMulticastNot = it }
    var hideDnsNotObserver = Observer<Boolean> { hideDnsNot = it }

    private fun findOpenPorts() {
        val pm = context.packageManager
        val wildcard = InetSocketAddress("0.0.0.0", 0)
        val openPortsTcp : MutableMap<Int, Pair<Int, String>> = HashMap()
        val openPortsUdp : MutableMap<Int, Pair<Int, String>> = HashMap()

        for (port in 1..65535) {
            val localAddr = InetSocketAddress("0.0.0.0", port)
            var uid = connectivityManager.getConnectionOwnerUid(
                IPPROTO_TCP,
                localAddr,
                wildcard
            )
            if (uid != INVALID_UID) {
                val owner = getPackageNameFromUid(uid, pm)
                Log.i(TAG, "Found open tcp port: $port, owner: $owner")
                openPortsTcp[port] = Pair(uid, owner)
            }
            uid = connectivityManager.getConnectionOwnerUid(
                IPPROTO_UDP,
                localAddr,
                wildcard
            )
            if (uid != INVALID_UID) {
                val owner = getPackageNameFromUid(uid, pm)
                Log.i(TAG, "Found open udp port: $port, owner: $owner")
                openPortsUdp[port] = Pair(uid, owner)
            }
        }

    }


    private fun logBlockedPacket(packetHeader: IPHeader, rawPacket: ByteBuffer, packageName: String) {
        val lanFlow = LANFlow.createFlow(
            appId = packageName, remoteEndpoint = packetHeader.destination, localEndpoint = packetHeader.source,
            transportLayerProtocol = packetHeader.protocolNumberAsString(), appliedPolicy = BLOCK
        )
        lanFlow.dataEgress = packetHeader.size.toLong()
        lanFlow.packetCountEgress = 1
        val dpiResult = getDpiResult(packetHeader, rawPacket)
        if(dpiResult != null) {
            lanFlow.dpiReport = dpiResult.jsonBuffer
            lanFlow.dpiProtocol = dpiResult.protocolName
        }
        CoroutineScope(Dispatchers.IO).launch {
            appDatabase.FlowDao().insertFlow(lanFlow)
        }
    }

    override fun run() {
//        findOpenPorts()
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
        }
        catch(_: Exception) {
            Log.wtf(TAG,"packetBuffer not backed by array")
            threadMainLoopActive = false
            return
        }
        while (threadMainLoopActive) {
            try {
                packetBuffer.clear()
                packetLength = vpnReadStream.read(packetBufferArray, packetBuffer.arrayOffset(), MAX_PACKET_LEN)

                if (packetLength > 0) {
                    try {
                        packetBuffer.limit(packetLength)
                        val packetHeader = IPHeader(packetBuffer)
                        val (forwardPolicy, packageName) = shouldForwardPacket(packetHeader)

                        val shouldForward =
                            (forwardPolicy == ALLOW) or (forwardPolicy == DEFAULT && defaultForwardPolicy == ALLOW)
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
                }
                else if (packetLength == 0) {
                    Thread.sleep(10)
                    Log.wtf(TAG, "vpnReadStream not configured as blocking!")
                }
                else {
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
                if(uid != 0 && uid != -1) {
                    return uid
                }

            } catch (e: IllegalArgumentException) {
                return -1
            }
        }
        return -1
    }

    private fun shouldForwardPacket(packetHeader: IPHeader): Pair<Policy, String> {
        val transportLayerProtocol = packetHeader.protocolNumberAsOSConstant()
        val canLookupAppUid = transportLayerProtocol == IPPROTO_TCP
                || transportLayerProtocol == IPPROTO_UDP
        if (!canLookupAppUid) {
            // We can only lookup the app's uid for TCP and UDP packets.
            // For other protocols we use the default policy.
            return Pair(DEFAULT, PACKAGE_NAME_UNKNOWN)
        }

        val ipAddress = packetHeader.destination.address
        if (ipAddress.isMulticastAddress || ipAddress.hostAddress == "255.255.255.255") {
            val policy = if (defaultForwardPolicy == BLOCK && !allowMulticast) BLOCK else ALLOW
            return Pair(policy, PACKAGE_NAME_UNKNOWN)
        }

        if (packetHeader.destination.port == 53) {
            val policy = if (defaultForwardPolicy == BLOCK && !allowDns) BLOCK else ALLOW
            return Pair(policy, PACKAGE_NAME_UNKNOWN)
        }

        val appUid = getPacketOwnerUid(packetHeader)
        val hasValidUid = appUid != -1 && appUid != 1000 && appUid != 0

        if (hasValidUid) {
            val appPackageName = getPackageNameFromUid(appUid, context.packageManager)
            val exceptionPolicy = accessPoliciesCache.getOrDefault(appPackageName, DEFAULT)
            val isSystemApp = getPackageMetadata(appPackageName, context).isSystem

            // 1. If an explicit per-app policy is set (ALLOW or BLOCK), honor it immediately.
            if (exceptionPolicy != DEFAULT) {
                Log.d("shouldForwardPacket", "Explicit policy for $appPackageName: $exceptionPolicy")
                return Pair(exceptionPolicy, appPackageName)
            }

            // 2. App-specific policy is DEFAULT: determine effective (applied) policy.
            var appliedPolicy = defaultForwardPolicy
            if (defaultForwardPolicy != ALLOW && isSystemApp) {
                appliedPolicy = systemAppsForwardPolicy
            }

            // 2a. If the effective policy is ALLOW, use your standard notification logic.
            if (appliedPolicy == ALLOW) {
                val notificationPolicy = defaultForwardPolicy
                val isDnsNotificationHidden = defaultForwardPolicy == ALLOW &&
                        (packetHeader.destination.port == 53) && hideDnsNot
                val isMulticastNotificationHidden = defaultForwardPolicy == ALLOW &&
                        (ipAddress.isMulticastAddress || ipAddress.hostAddress == "255.255.255.255") && hideMulticastNot
                if (!isDnsNotificationHidden && !isMulticastNotificationHidden) {
                    vpnNotificationManager.postNotification(appPackageName, notificationPolicy, packetHeader.destination)
                }
                return Pair(ALLOW, appPackageName)
            }

            // 3. For effective BLOCK (or global default BLOCK) – perform preflight check.
            // Only perform preflight for plaintext HTTP on ports 80, 8080, or 8081.
            val port = packetHeader.destination.port
            if (port !in listOf(80, 8080, 8081)) {
                vpnNotificationManager.postNotification(appPackageName, appliedPolicy, packetHeader.destination)
                return Pair(appliedPolicy, appPackageName)
            }
            val destinationUrl = "http://${ipAddress.hostAddress}:$port/"
            // Rebuild a key based on IP:port.
            val key = "${ipAddress.hostAddress}:$port"
            Log.d("shouldForwardPacket", "Sending preflight request to $destinationUrl")
            val pnaManager = PnaManager()
            val preflightResult: Boolean? = try {
                pnaManager.sendPreflightRequest(ipAddress.hostAddress, port)
            } catch (e: Exception) {
                Log.e("shouldForwardPacket", "Preflight exception: ${e.message}")
                null
            }

            // 4. Handle preflight result based on global default.
            if (defaultForwardPolicy == ALLOW) {
                when (preflightResult) {
                    true -> {
                        Log.d("shouldForwardPacket", "Preflight positive for $destinationUrl, allowing silently.")
                        return Pair(ALLOW, appPackageName)
                    }
                    false -> {
                        vpnNotificationManager.postPreflightNotification(
                            key,
                            "Preflight Failure",
                            "Preflight failed for $key (cached for 5 min)"
                        )
                        return Pair(BLOCK, appPackageName)
                    }
                    null -> {
                        vpnNotificationManager.postNotification(appPackageName, defaultForwardPolicy, packetHeader.destination)
                        return Pair(ALLOW, appPackageName)
                    }
                }
            } else if (defaultForwardPolicy == BLOCK) {
                when (preflightResult) {
                    true -> {
                        Log.d("shouldForwardPacket", "Preflight positive for $destinationUrl, overriding BLOCK.")
                        return Pair(ALLOW, appPackageName)
                    }
                    else -> {
                        vpnNotificationManager.postPreflightNotification(
                            key,
                            "Preflight Failure",
                            "Preflight failed for $key (cached for 5 min)"
                        )
                        return Pair(BLOCK, appPackageName)
                    }
                }
            } else {
                vpnNotificationManager.postNotification(appPackageName, appliedPolicy, packetHeader.destination)
                return Pair(appliedPolicy, appPackageName)
            }
        }
        return Pair(DEFAULT, PACKAGE_NAME_UNKNOWN)
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

