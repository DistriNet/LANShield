package org.distrinet.lanshield.vpnservice

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import org.distrinet.lanshield.PACKAGE_NAME_UNKNOWN
import org.distrinet.lanshield.Policy.BLOCK
import org.distrinet.lanshield.TAG
import org.distrinet.lanshield.database.AppDatabase
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.getPackageNameFromUid
import java.io.FileInputStream
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

    // Allocate the buffer for a single packet.
    private val packetBuffer = ByteBuffer.allocate(MAX_PACKET_LEN)





    private fun logBlockedPacket(packetHeader: IPHeader, rawPacket: ByteBuffer, packageName: String) {
        val lanFlow = LANFlow.createFlow(
            appId = packageName, remoteEndpoint = packetHeader.destination, localEndpoint = packetHeader.source,
            transportLayerProtocol = packetHeader.protocolNumberAsString(), appliedPolicy = BLOCK
        )
        lanFlow.dataEgress = packetHeader.size.toLong()
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
                        val packageName = getPacketOwner(packetHeader)
                        packetBuffer.rewind()
                        vpnNotificationManager.postNotification(packageName = packageName, BLOCK, packetHeader.destination)
                        logBlockedPacket(packetHeader, packetBuffer, packageName)

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

    private fun getPacketOwner(packetHeader: IPHeader): String {

        val appUid = getPacketOwnerUid(packetHeader)
        val hasValidUid = appUid != -1 && appUid != 1000 && appUid != 0

        if (hasValidUid) {
            val appPackageName = getPackageNameFromUid(appUid, context.packageManager)
            return appPackageName
        }
        else {
            return PACKAGE_NAME_UNKNOWN
        }
    }

    fun stop() {
        if (threadMainLoopActive) {
            threadMainLoopActive = false

        } else {
            Log.w(TAG, "Vpn runnable stopped, but it's not running")
        }
    }
}

