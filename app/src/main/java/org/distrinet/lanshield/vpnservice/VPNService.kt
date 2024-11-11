package org.distrinet.lanshield.vpnservice

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.distrinet.lanshield.DEFAULT_POLICY_KEY
import org.distrinet.lanshield.R
import org.distrinet.lanshield.SERVICE_NOTIFICATION_CHANNEL_ID
import org.distrinet.lanshield.SYSTEM_APPS_POLICY_KEY
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.model.LanAccessPolicy
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.VPN_ALWAYS_ON_STATUS
import org.distrinet.lanshield.TAG
import org.distrinet.lanshield.database.dao.LANShieldSessionDao
import org.distrinet.lanshield.database.model.LANShieldSession
import tech.httptoolkit.android.vpn.socket.IProtectSocket
import tech.httptoolkit.android.vpn.socket.SocketProtector
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject

@AndroidEntryPoint
class VPNService : VpnService(), IProtectSocket {
    private var vpnRunnable: VPNRunnable? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    private lateinit var accessPolicies: LiveData<List<LanAccessPolicy>>
    private lateinit var defaultForwardPolicyLive: LiveData<Policy>
    private lateinit var systemAppsForwardPolicyLive: LiveData<Policy>

    private var isVPNRunning = false

    private var lanShieldSession: LANShieldSession? = null

    @Inject
    lateinit var vpnServiceStatus: MutableLiveData<VPN_SERVICE_STATUS>

    @Inject
    lateinit var vpnAlwaysOnStatus: MutableLiveData<VPN_ALWAYS_ON_STATUS>

    @Inject
    lateinit var dataStore: DataStore<Preferences>

    @Inject
    lateinit var lanAccessPolicyDao: LanAccessPolicyDao

    @Inject
    lateinit var vpnNotificationManager: LANShieldNotificationManager

    @Inject
    lateinit var lanShieldSessionDao: LANShieldSessionDao

    companion object {
        const val STOP_VPN_SERVICE = "STOP_VPN_SERVICE"
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        defaultForwardPolicyLive = dataStore.data.map {
            Policy.valueOf(
                it[DEFAULT_POLICY_KEY] ?: Policy.DEFAULT.toString()
            )
        }.distinctUntilChanged().asLiveData()
        systemAppsForwardPolicyLive = dataStore.data.map {
            Policy.valueOf(
                it[SYSTEM_APPS_POLICY_KEY] ?: Policy.DEFAULT.toString()
            )
        }.distinctUntilChanged().asLiveData()
        accessPolicies = lanAccessPolicyDao.getAllLive()

        updateAlwaysOnStatus()

        intent?.let {
            when (it.action) {
                STOP_VPN_SERVICE -> {
                    if (isVPNRunning()) {
                        stopVPNThread()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }

                else -> {
                    if (!isVPNRunning()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            startForeground(1, createNotification(), FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                        }
                        else {
                            startForeground(1, createNotification())
                        }
                        startVPNThread()
                    }
                }
            }
        }

        // Return the appropriate service restart behavior
        return START_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVPNThread()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        setVPNRunning(false)
        stopLanShieldSession()
        super.onDestroy()
    }

    private fun stopLanShieldSession() {
        if(lanShieldSession != null) {
            lanShieldSession!!.timeEnd = System.currentTimeMillis()
            val session = lanShieldSession!!
            CoroutineScope(Dispatchers.IO).launch {
                lanShieldSessionDao.update(session)
            }
            lanShieldSession = null
        }
    }

    private fun createNotification(): Notification {
        // Create an intent for stopping the VPN service
        val stopIntent = Intent(this, VPNService::class.java).apply {
            action = STOP_VPN_SERVICE
        }
        val stopPendingIntent: PendingIntent =
            PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        // Build the notification using NotificationCompat.Builder
        return NotificationCompat.Builder(this, SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentText(getString(R.string.app_name) + " enabled")
            .setSmallIcon(R.mipmap.logo_foreground)
            .setShowWhen(false)
            .setOngoing(true)
            .addAction(
                R.mipmap.logo_foreground,
                "Stop LANShield",
                stopPendingIntent
            ).build()
    }

    private fun stopVPNThread() {
        stopLanShieldSession()

        vpnRunnable?.let {
            accessPolicies.removeObserver(it.accessPoliesObserver)
            defaultForwardPolicyLive.removeObserver(it.defaultPolicyObserver)
            systemAppsForwardPolicyLive.removeObserver(it.systemAppsPolicyObserver)
            it.stop()
        }
        vpnRunnable = null

//        vpnThread?.join() //TODO -> shouldn't be required
        vpnThread = null

        try {
            vpnInterface?.close()
        }
        catch (_: IOException) {
        }
        vpnInterface = null

        setVPNRunning(false)
    }

    private fun isVPNRunning(): Boolean {
        return isVPNRunning
    }

    private fun setVPNRunning(isRunning: Boolean) {
        if (isRunning) {
            vpnServiceStatus.value = VPN_SERVICE_STATUS.ENABLED
        } else {
            vpnServiceStatus.value = VPN_SERVICE_STATUS.DISABLED
        }
        isVPNRunning = isRunning
    }

    private fun addIpv4Routes(builder: Builder) {
        // RFC1918 Private Internets
        builder.addRoute("10.0.0.0", 8)
            .addRoute("172.16.0.0", 12)
            .addRoute("192.168.0.0", 16)
            // RFC5735 Special Use addresses that are not globally reachable
            .addRoute("0.0.0.0", 8)
            .addRoute("169.254.0.0", 16)
            .addRoute("192.0.0.0", 24)
            .addRoute("192.0.2.0", 24)
            .addRoute("192.88.99.0", 24)
            .addRoute("198.18.0.0", 15)
            .addRoute("198.51.100.0", 24)
            .addRoute("203.0.113.0", 24)
            // IPv4 Multicast and Limited Broadcast
            .addRoute("224.0.0.0", 4)
            .addRoute("255.255.255.255", 32)
            // Remaining non-globally reachable addresses from the list at
            // https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry.xhtml
            .addRoute("100.64.0.0", 10)
            .addRoute("240.0.0.0", 4)
    }

    private fun addIpv6Routes(builder: Builder) {
        // PART 1, based on https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry.xhtml
        // The IETF Protocol Assignments range is not globally reachable, but subnets inside
        // it are reachable. We only intercept the non-gobally reachable subnets.
        builder.addRoute("100::", 64) // Discard-Only Address Block
            .addRoute("2001:2::", 32) // Benchmarking
            .addRoute("2001:db8::", 32) // Documentation
            .addRoute("5f00::", 16) // Segment Routing SIDs
            .addRoute("fc00::", 7) // Unique-Local
            .addRoute("fe80::", 10) // Link-Local Unicast
            // PART 2, deprecated ranges that are not on the IANA overview but might
            // still be used in practice
            .addRoute("fec0::", 10) // Site-local addresses
            // PART 3, based on https://www.iana.org/assignments/ipv6-multicast-addresses/ipv6-multicast-addresses.xhtml
            // Intercept all multicast destination. Ideally we would also exclude the global-scope
            // multicast addresses here already, but that requires a higher API level, so instead
            // we have to filter that address while processing packets.
            .addRoute("ff00::", 8)
            // TODO: Filter global-scope multicast later on in the processing of packets.
            // .excludeRoute("ff0e::", 16)
    }

    private fun getNetworkAddress(address: InetAddress, prefixLength: Short) : InetAddress {
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        var addressBytes = address.address.copyOfRange(0, fullBytes)

        if (remainingBits != 0)
            addressBytes += (address.address[fullBytes].toInt() and (0xFF shl (8 - remainingBits))).toByte()

        return InetAddress.getByAddress(addressBytes.copyOf(16))
    }

    private fun addInterfaceAddressRoutes(builder: Builder) {
        // TODO: This code is not re-run when switching (Wi-Fi) networks. For IPv4 this is likely not
        // an issue, since usage of public IP addresses is uncommon in IPv4 networks. However, for
        // IPv6, every network may use different addresses that are not captured by our IPv6 routes
        // that are always installed. See how other VPNs do this and for starting points see:
        // - https://stackoverflow.com/questions/6169059/android-event-for-internet-connectivity-state-change
        // - https://medium.com/@veniamin.vynohradov/monitoring-internet-connection-state-in-android-da7ad915b5e5
        for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
            if (networkInterface.isLoopback) continue

            for (address in networkInterface.interfaceAddresses) {
                if (address.address.isAnyLocalAddress or
                    address.address.isLinkLocalAddress or
                    address.address.isSiteLocalAddress) continue
                val networkAddress = getNetworkAddress(address.address, address.networkPrefixLength)
                builder.addRoute(networkAddress, address.networkPrefixLength.toInt())
                Log.d(TAG, "Also monitoring " + networkAddress.toString()+ "/" + address.networkPrefixLength.toString())
            }
        }
    }



    private fun startVPNThread() {
        stopVPNThread()
        updateAlwaysOnStatus()

        val builder = Builder()
        builder.setSession(getString(R.string.app_name) + " LAN Firewall")
            .addAddress("169.254.61.43", 24) // IPv4 link-local address
            .addAddress("fe80::2B", 64) // IPv6 link-local address
        addIpv4Routes(builder)
        addIpv6Routes(builder)
        addInterfaceAddressRoutes(builder)
        builder.addDisallowedApplication(packageName)
            .setBlocking(true)
            .setMtu(MAX_PACKET_LEN)
            .setMetered(false)

        // establish() returns null if we no longer have permissions to establish the VPN somehow
        val vpnInterface = builder.establish() ?: return

        this.vpnInterface = vpnInterface
        SocketProtector.getInstance().setProtector(this)

        vpnRunnable = VPNRunnable(vpnInterface, vpnNotificationManager, this)
        accessPolicies.observeForever(vpnRunnable!!.accessPoliesObserver)
        defaultForwardPolicyLive.observeForever(vpnRunnable!!.defaultPolicyObserver)
        systemAppsForwardPolicyLive.observeForever(vpnRunnable!!.systemAppsPolicyObserver)


        vpnThread = Thread(vpnRunnable, "VPN thread")

        stopLanShieldSession()
        lanShieldSession = LANShieldSession.createLANShieldSession()
        CoroutineScope(Dispatchers.IO).launch {
            lanShieldSessionDao.insert(lanShieldSession!!)
        }
        vpnThread!!.start()
        setVPNRunning(true)


    }

    private fun updateAlwaysOnStatus() {
        vpnAlwaysOnStatus.postValue(if(isAlwaysOn) VPN_ALWAYS_ON_STATUS.ENABLED else VPN_ALWAYS_ON_STATUS.DISABLED)
    }
}