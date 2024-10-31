package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import android.util.SparseArray
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.distrinet.lanshield.PACKAGE_NAME_UNKNOWN
import org.distrinet.lanshield.database.AppDatabase
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.database.model.LanAccessPolicy
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.Policy.ALLOW
import org.distrinet.lanshield.Policy.BLOCK
import org.distrinet.lanshield.Policy.DEFAULT
import org.distrinet.lanshield.getPackageMetadata
import org.distrinet.lanshield.getPackageNameFromUid
import tech.httptoolkit.android.TAG
import tech.httptoolkit.android.vpn.ClientPacketWriter
import tech.httptoolkit.android.vpn.SessionHandler
import tech.httptoolkit.android.vpn.SessionManager
import tech.httptoolkit.android.vpn.socket.SocketNIODataService
import tech.httptoolkit.android.vpn.transport.PacketHeaderException
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

    private val connectivityManager = context.getSystemService(VpnService.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var threadMainLoopActive = false

    private val appDatabase = AppDatabase.getDatabase(context)


    private val vpnReadStream = FileInputStream(vpnInterface.fileDescriptor)
    private val vpnWriteStream = FileOutputStream(vpnInterface.fileDescriptor)

    private val vpnPacketWriterRunnable = ClientPacketWriter(vpnWriteStream)
    private val vpnPacketWriterThread = Thread(vpnPacketWriterRunnable)

    // Background service & task for non-blocking socket
    private val nioServiceRunnable = SocketNIODataService(vpnPacketWriterRunnable, appDatabase)
    private val dataServiceThread = Thread(nioServiceRunnable, "Socket NIO thread")

    private val httpToolkitSessionManager = SessionManager(
        context.getSystemService(VpnService.CONNECTIVITY_SERVICE) as ConnectivityManager,
        context.packageManager,
        appDatabase
    )
    private val httpToolkitSessionHandler =
        SessionHandler(httpToolkitSessionManager, nioServiceRunnable, vpnPacketWriterRunnable)

    // Allocate the buffer for a single packet.
    private val packetBuffer = ByteBuffer.allocate(MAX_PACKET_LEN)


    @Volatile
    private var defaultForwardPolicy = ALLOW

    @Volatile
    private var systemAppsForwardPolicy = ALLOW

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


    private fun logBlockedPacket(packet: IPHeader, packageName: String) {
        if(packet.ipVersion() == 6) return
        val lanFlow = LANFlow.createFlow(
            appId = packageName, remoteEndpoint = packet.destination, localEndpoint = packet.source,
            transportLayerProtocol = packet.protocolNumberAsString(), appliedPolicy = BLOCK
        )
        lanFlow.dataEgress = packet.size.toLong()
        lanFlow.packetCountEgress = 1
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
        dataServiceThread.start()
        vpnPacketWriterThread.start()

        var packetLength: Int

        threadMainLoopActive = true
        while (threadMainLoopActive) {
            try {
                packetBuffer.clear()
                packetLength = vpnReadStream.read(packetBuffer.array())
                if (packetLength > 0) {
                    try {
                        packetBuffer.limit(packetLength)
                        val packetHeader = IPHeader(packetBuffer)
                        val (forwardPolicy, packageName) = shouldForwardPacket(packetHeader)
                        val shouldForward =
                            (forwardPolicy == ALLOW) or (forwardPolicy == DEFAULT && defaultForwardPolicy == ALLOW)

                        if (shouldForward) {
                            packetBuffer.rewind()
                            httpToolkitSessionHandler.handlePacket(packetBuffer)
                        } else {
                            logBlockedPacket(packetHeader, packageName)
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
                                    (e is ConnectException && errorMessage.contains("EMFILE")) ||
                                    // IPv6 is not supported here yet:
                                    (e is PacketHeaderException && errorMessage.contains("IP version should be 4 but was 6"))

                        if (!isIgnorable) {
                            Log.e(TAG, e.toString())
                        }
                    }
                } else {
                    // vpnReadStream should be configured as blocking
                    Thread.sleep(10)
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
        Log.i(TAG, "Vpn thread shutting down")
    }

    private fun getPacketOwnerUid(pkt: IPHeader): Int {
        return try {
            connectivityManager.getConnectionOwnerUid(
                pkt.protocolNumberAsOSConstant(),
                pkt.source,
                pkt.destination
            )
        } catch (e: IllegalArgumentException) {
            -1
        }

    }



    private fun shouldForwardPacket(packetHeader: IPHeader): Pair<Policy, String> {

        val transportLayerProtocol = packetHeader.protocolNumberAsOSConstant()
        val canLookupAppUid = transportLayerProtocol == OsConstants.IPPROTO_TCP
                || transportLayerProtocol == OsConstants.IPPROTO_UDP
        if (!canLookupAppUid) {
            // We can only lookup the app's uid for TCP and UDP packets.
            // For other protocols we use the default policy.
            return Pair(DEFAULT, PACKAGE_NAME_UNKNOWN)
        }

        val appUid = getPacketOwnerUid(packetHeader)
        val hasValidUid = appUid != -1 && appUid != 1000 && appUid != 0

        if (hasValidUid) {
            val appPackageName = getPackageNameFromUid(appUid, context.packageManager)

            val exceptionPolicy = accessPoliciesCache.getOrDefault(appPackageName, DEFAULT)
            val isSystemApp = getPackageMetadata(appPackageName, context).isSystem
            var appliedPolicy = exceptionPolicy

            if(exceptionPolicy == DEFAULT) {
                if(defaultForwardPolicy != ALLOW && isSystemApp) {
                    appliedPolicy = systemAppsForwardPolicy
                }
                val notificationPolicy = when(appliedPolicy) {
                    DEFAULT -> defaultForwardPolicy
                    else -> appliedPolicy
                }
                vpnNotificationManager.postNotification(packageName = appPackageName, notificationPolicy, packetHeader.destination)
            }
            return Pair(appliedPolicy, appPackageName)
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