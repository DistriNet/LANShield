package org.distrinet.lanshield.vpnservice

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
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
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.distrinet.lanshield.DEFAULT_POLICY_KEY
import org.distrinet.lanshield.MainActivity
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.Policy.ALLOW
import org.distrinet.lanshield.Policy.BLOCK
import org.distrinet.lanshield.R
import org.distrinet.lanshield.SERVICE_NOTIFICATION_CHANNEL_ID
import org.distrinet.lanshield.SYSTEM_APPS_POLICY_KEY
import org.distrinet.lanshield.TAG
import org.distrinet.lanshield.VPN_ALWAYS_ON_STATUS
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.database.dao.LANShieldSessionDao
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.model.LANShieldSession
import org.distrinet.lanshield.database.model.LanAccessPolicy
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject

/* The IP address of the virtual network interface */
const val TUN_IP4_ADDRESS = "10.215.173.1"
const val TUN_IP6_ADDRESS = "fd00:2:fd00:1:fd00:1:fd00:1"

@AndroidEntryPoint
class VPNService : VpnService() {
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

    @Volatile
    private var defaultForwardPolicy = ALLOW

    @Volatile
    private var systemAppsForwardPolicy = ALLOW

    @Synchronized
    fun reconfigureVPN() {
        if(isVPNRunning) {
            stopVPNThread()
            startVPNThread()
        }
    }

    @Synchronized
    fun setDefaultForwardPolicy(policy: Policy) {
        defaultForwardPolicy = policy
        reconfigureVPN()
    }

    @Synchronized
    fun setSystemAppsForwardPolicy(policy: Policy) {
        systemAppsForwardPolicy = policy
        reconfigureVPN()
    }

    @Volatile
    private var accessPoliciesCache = HashMap<String, Policy>()

    @Synchronized
    private fun updateAccessPoliciesCache(newCache: HashMap<String, Policy>) {
        accessPoliciesCache = newCache
        accessPoliciesCache.forEach({
            Log.i(TAG, "Policy cache entry, ${it.key}:${it.value}")
        })
        reconfigureVPN()
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

        accessPolicies.observeForever(accessPoliesObserver)
        defaultForwardPolicyLive.observeForever(defaultPolicyObserver)
        systemAppsForwardPolicyLive.observeForever(systemAppsPolicyObserver)


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
                        LANShieldNotificationManager(this).createNotificationChannels()
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
        accessPolicies.removeObserver(accessPoliesObserver)
        defaultForwardPolicyLive.removeObserver(defaultPolicyObserver)
        systemAppsForwardPolicyLive.removeObserver(systemAppsPolicyObserver)

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

        val openAppIntent = Intent(this, MainActivity::class.java)
        openAppIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification using NotificationCompat.Builder
        return NotificationCompat.Builder(this, SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentText(getString(R.string.app_name) + " enabled")
            .setSmallIcon(R.mipmap.logo_foreground)
            .setShowWhen(false)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.mipmap.logo_foreground,
                "Stop LANShield",
                stopPendingIntent
            ).build()
    }

    private fun stopVPNThread() {
        stopLanShieldSession()

        vpnRunnable?.stop()
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
        vpnServiceStatus.value = if (isRunning) {
            VPN_SERVICE_STATUS.ENABLED
        } else {
            VPN_SERVICE_STATUS.DISABLED
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
            .addRoute("fec0::", 10) // Site-local addresses
            .addRoute("ff00::", 8)
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
            .addAddress(TUN_IP4_ADDRESS, 32)
            .addAddress(TUN_IP6_ADDRESS, 128)
        addIpv4Routes(builder)
        addIpv6Routes(builder)
        addInterfaceAddressRoutes(builder)
            builder.setBlocking(true)
            .setMtu(MAX_PACKET_LEN)
            .setMetered(false)
        Log.d(TAG, "Default forward policy: $defaultForwardPolicy")
        if(defaultForwardPolicy == ALLOW){
            accessPoliciesCache.filterValues { it == BLOCK }.keys.forEach {
                Log.d(TAG, "Blocking LAN traffic from $it")
                builder.addAllowedApplication(
                    it
                )
            }
        }
        else if (defaultForwardPolicy == BLOCK) {
            builder.addDisallowedApplication(packageName)
            accessPoliciesCache.filterValues { it == ALLOW }.keys.forEach {
                Log.d(TAG, "Allowing LAN traffic from $it")
                builder.addDisallowedApplication(it)
            }
            if (systemAppsForwardPolicy == ALLOW) {
                packageManager.getInstalledPackages(0)
                    .filter { ((it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0 }
                    .forEach {
                        Log.d(TAG, "Allowing LAN traffic from system app $it")
                        builder.addDisallowedApplication(it.packageName)
                    }
            }
        }
        // establish() returns null if we no longer have permissions to establish the VPN somehow
        val vpnInterface = builder.establish() ?: return

        this.vpnInterface = vpnInterface

        vpnRunnable = VPNRunnable(vpnInterface, vpnNotificationManager, this)

        vpnThread = Thread(vpnRunnable, "VPN thread")

        stopLanShieldSession()
        lanShieldSession = LANShieldSession.createLANShieldSession()
        vpnThread!!.start()
        setVPNRunning(true)
    }

    private fun updateAlwaysOnStatus() {
        vpnAlwaysOnStatus.postValue(if(isAlwaysOn) VPN_ALWAYS_ON_STATUS.ENABLED else VPN_ALWAYS_ON_STATUS.DISABLED)
    }
}