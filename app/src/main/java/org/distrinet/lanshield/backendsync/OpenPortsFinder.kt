package org.distrinet.lanshield.backendsync

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process.INVALID_UID
import android.system.OsConstants.IPPROTO_TCP
import android.system.OsConstants.IPPROTO_UDP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.distrinet.lanshield.database.model.OpenPorts
import org.distrinet.lanshield.getPackageMetadata
import org.distrinet.lanshield.getPackageNameFromUid
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

suspend fun findOpenPorts(
    pm: PackageManager,
    connectivityManager: ConnectivityManager
): List<OpenPorts> = withContext(Dispatchers.Default) {
    coroutineScope {
        val wildcardIpv4 = InetSocketAddress("0.0.0.0", 0)
        val wildcardIpv6 = InetSocketAddress("::", 0)

        val openPortsByUid = ConcurrentHashMap<Int, OpenPorts>()

        val jobs = (1..65535).map { port ->
            launch {
                checkPort(
                    port,
                    connectivityManager,
                    pm,
                    openPortsByUid,
                    wildcardIpv4,
                    wildcardIpv6
                )
            }
        }

        jobs.joinAll()

        openPortsByUid.values.sorted()
    }
}


private fun checkPort(
    port: Int,
    connectivityManager: ConnectivityManager,
    pm: PackageManager,
    openPortsByUid: MutableMap<Int, OpenPorts>,
    wildcardIpv4: InetSocketAddress,
    wildcardIpv6: InetSocketAddress
) {
    val localAddrIpv4 = InetSocketAddress("0.0.0.0", port)
    val localAddrIpv6 = InetSocketAddress("::", port)

    for ((proto, addr) in listOf(
        "TCP" to localAddrIpv4,
        "UDP" to localAddrIpv4,
        "TCP" to localAddrIpv6,
        "UDP" to localAddrIpv6,
    )) {
        val ipProto = if (proto == "TCP") IPPROTO_TCP else IPPROTO_UDP
        val uid = connectivityManager.getConnectionOwnerUid(
            ipProto,
            addr,
            if (addr.address.hostAddress == "::") wildcardIpv6 else wildcardIpv4
        )

        if (uid != INVALID_UID) {
            synchronized(openPortsByUid) {
                val appWithPorts = openPortsByUid.getOrPut(uid) {
                    val packageName = getPackageNameFromUid(uid, pm)
                    val packageLabel = getPackageMetadata(packageName, pm).packageLabel
                    OpenPorts.createInstance(packageName, packageLabel)
                }
                if (proto == "TCP") appWithPorts.tcpPorts.add(addr.port)
                else appWithPorts.udpPorts.add(addr.port)
            }
        }
    }
}