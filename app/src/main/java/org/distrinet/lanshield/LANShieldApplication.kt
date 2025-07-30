package org.distrinet.lanshield

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.MutableLiveData
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.android.volley.RequestQueue
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.distrinet.lanshield.backendsync.AppUsageStats
import org.distrinet.lanshield.backendsync.OpenPortsWorker
import org.distrinet.lanshield.backendsync.SendToServerWorkerr
import org.distrinet.lanshield.database.AppDatabase
import org.distrinet.lanshield.database.dao.FlowDao
import org.distrinet.lanshield.database.dao.LANShieldSessionDao
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.dao.OpenPortsDao
import org.distrinet.lanshield.vpnservice.LANShieldNotificationManager
import tech.httptoolkit.android.TAG
import javax.inject.Inject
import javax.inject.Singleton

const val FEEDBACK_URL = "https://forms.gle/zh2J3KMzZjMnv2Qi9"
const val STUDY_MORE_INFO_URL = "https://lanshield.eu/user-study"
const val ABOUT_LANSHIELD_URL = "https://lanshield.eu"
const val PRIVACY_POLICY_URL = "https://lanshield.eu/privacy-policy"

val NO_SYSTEM_OVERRIDE_PACKAGE_NAMES = listOf("com.android.chrome")

const val PACKAGE_NAME_UNKNOWN = "Unknown"
const val PACKAGE_NAME_ROOT = "Root"
const val PACKAGE_NAME_SYSTEM = "System"
const val PACKAGE_NAME_PLAY_SERVICES = "Google Play Services"

const val BACKEND_URL = "https://api.lanshield.eu"
//const val BACKEND_URL = "http://192.168.157.147"
//const val BACKEND_URL = "http://192.168.55.147:5000"


const val ADD_FLOWS = "/add_flow"
const val ADD_ACL = "/add_acl"
const val ADD_APP_USAGE = "/add_app_usage"
const val ADD_LANSHIELD_SESSION = "/add_session"
const val ADD_OPEN_PORTS = "/add_open_ports"

const val GET_APP_INSTALLATION_UUID = "/get_app_installation_uuid"
const val SHOULD_SYNC = "/should_sync"
const val OPEN_PORTS_SUCCESS = "Open ports uploaded successfully"
const val ACL_SUCCESS = "ACL uploaded successfully"
const val APP_USAGE_SUCCESS = "App usage uploaded successfully"
const val BACKEND_SYNC_INTERVAL_DAYS: Long = 1
const val OPEN_PORT_SCAN_INTERVAL_HOURS: Long = 2

val RESERVED_PACKAGE_NAMES = listOf(PACKAGE_NAME_UNKNOWN, PACKAGE_NAME_ROOT, PACKAGE_NAME_SYSTEM)

const val PREFERENCES_STORE_NAME = "LANSHIELD_DATASTORE"

val INTRO_COMPLETED_KEY = booleanPreferencesKey("intro_completed")
val DEFAULT_POLICY_KEY = stringPreferencesKey("default_policy")
val SYSTEM_APPS_POLICY_KEY = stringPreferencesKey("block_system_apps_policy")

val SHARE_LAN_METRICS_KEY = booleanPreferencesKey("share_lan_metrics")
val SHARE_APP_USAGE_KEY = booleanPreferencesKey("share_app_metrics")

val APP_INSTALLATION_UUID = stringPreferencesKey("app_installation_uuid")

val TIME_OF_LAST_SYNC = longPreferencesKey("time_of_last_sync")

val ALLOW_MULTICAST = booleanPreferencesKey("allow_multicast")
val ALLOW_DNS = booleanPreferencesKey("allow_dns")
val HIDE_MULTICAST_NOT = booleanPreferencesKey("hide_multicast_not")
val HIDE_DNS_NOT = booleanPreferencesKey("hide_dns_not")

val AUTOSTART_ENABLED = booleanPreferencesKey("autostart_enabled")


const val SERVICE_NOTIFICATION_CHANNEL_ID = "SERVICE_NOTIFICATION_CHANNEL_ID"
const val LAN_TRAFFIC_DETECTED_CHANNEL_ID = "LAN_TRAFFIC_DETECTED_CHANNEL_ID"

enum class LANShieldIntentExtra {
    PACKAGE_NAME,
    PACKAGE_IS_SYSTEM,
    POLICY
}

enum class LANShieldIntentAction {
    UPDATE_LAN_POLICY
}


enum class Policy {
    DEFAULT, BLOCK, ALLOW
}

enum class VPN_ALWAYS_ON_STATUS {
    ENABLED,
    DISABLED
}

enum class VPN_SERVICE_ACTION {
    START_VPN,
    STOP_VPN,
    NO_ACTION
}

enum class VPN_SERVICE_STATUS {
    DISABLED,
    ENABLED
}

data class PackageMetadata(
    val packageName: String,
    val packageLabel: String,
    val isSystem: Boolean
)

fun applicationInfoIsSystem(applicationInfo: ApplicationInfo) : Boolean {
    if(applicationInfo.packageName != null && NO_SYSTEM_OVERRIDE_PACKAGE_NAMES.contains(applicationInfo.packageName)) return false
    val isSystemInt = applicationInfo.flags and (ApplicationInfo.FLAG_UPDATED_SYSTEM_APP or ApplicationInfo.FLAG_SYSTEM)
    return isSystemInt != 0
}

fun packageNameIsSystem(packageName: String, packageManager: PackageManager): Boolean {
    val applicationMetadata = getPackageMetadata(packageName, packageManager)
    return applicationMetadata.isSystem
}

private val packageMetadataCache = mutableMapOf<String, PackageMetadata>()

fun getPackageMetadata(packageName: String, packageManager: PackageManager) : PackageMetadata {
    return packageMetadataCache.getOrPut(packageName) {
        lookupPackageMetadata(packageName, packageManager)
    }
}

private fun lookupPackageMetadata(packageName: String, packageManager: PackageManager) : PackageMetadata {
    if(packageName.contentEquals(PACKAGE_NAME_UNKNOWN)) return PackageMetadata(packageName, PACKAGE_NAME_UNKNOWN, false)
    if(packageName.contentEquals(PACKAGE_NAME_ROOT)) return PackageMetadata(packageName, PACKAGE_NAME_ROOT, true)
    if(packageName.contentEquals(PACKAGE_NAME_SYSTEM)) return PackageMetadata(packageName, PACKAGE_NAME_SYSTEM, true)
    if ("android.uid.phone" in packageName) return PackageMetadata(packageName, PACKAGE_NAME_SYSTEM, true)
    if ("com.google.uid.shared" in packageName) return PackageMetadata(packageName, PACKAGE_NAME_PLAY_SERVICES, true)

    try {
        val applicationInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val label = packageManager.getApplicationLabel(applicationInfo).toString()
        val isSystem = applicationInfoIsSystem(applicationInfo)
        return PackageMetadata(packageName, label, isSystem)
    } catch (e: PackageManager.NameNotFoundException) {
        return PackageMetadata(packageName, packageName, false)
    }
}

fun getPackageNameFromUid(appUid: Int, packageManager: PackageManager): String {
    return when (appUid) {
        0 -> PACKAGE_NAME_ROOT
        -1 -> PACKAGE_NAME_UNKNOWN
        1000 -> PACKAGE_NAME_SYSTEM
        else -> {
            val packages = packageManager.getPackagesForUid(appUid)
            if (packages != null && packages.size == 1) {
                packages[0]
            } else {
                val sharedName = packageManager.getNameForUid(appUid) ?: return PACKAGE_NAME_UNKNOWN
                return sharedName
            }
        }
    }
}


fun isAppUsageAccessGranted(context: Context): Boolean {
    try {
        val packageManager: PackageManager = context.packageManager
        val applicationInfo = packageManager.getApplicationInfo(context.packageName, 0)
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager?
        val mode = appOpsManager!!.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            applicationInfo.uid, applicationInfo.packageName
        )
        return (mode == AppOpsManager.MODE_ALLOWED)
    } catch (e: PackageManager.NameNotFoundException) {
        return false
    }
}

@HiltAndroidApp
class LANShieldApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: LANShieldWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
}

class LANShieldWorkerFactory @Inject constructor(
    private val flowDao: FlowDao,
    private val lanAccessPolicyDao: LanAccessPolicyDao,
    private val lanShieldSessionDao: LANShieldSessionDao,
    private val openPortsDao: OpenPortsDao,
    private val dataStore: DataStore<Preferences>,
    private val vpnServiceStatus: MutableLiveData<VPN_SERVICE_STATUS>
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            OpenPortsWorker::class.qualifiedName -> OpenPortsWorker(
                appContext = appContext,
                workerParams = workerParameters,
                openPortsDao = openPortsDao,
                vpnServiceStatus = vpnServiceStatus
            )
            SendToServerWorkerr::class.qualifiedName -> SendToServerWorkerr(
                context = appContext,
                params = workerParameters,
                flowDao = flowDao,
                lanAccessPolicyDao = lanAccessPolicyDao,
                lanShieldSessionDao = lanShieldSessionDao,
                openPortsDao = openPortsDao,
                dataStore = dataStore
            )
            else -> null
        }
    }
}


@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    private val vpnServiceActionRequest = MutableLiveData(VPN_SERVICE_ACTION.NO_ACTION)
    private val vpnServiceStatus = MutableLiveData(VPN_SERVICE_STATUS.DISABLED)
    private val vpnServiceAlwaysOnStatus = MutableLiveData(VPN_ALWAYS_ON_STATUS.ENABLED)


    @Provides
    @Singleton
    fun provideVPNServiceAlwaysOnStatus(): MutableLiveData<VPN_ALWAYS_ON_STATUS> {
        return vpnServiceAlwaysOnStatus
    }

    @Provides
    @Singleton
    fun provideAppUsageStats(): AppUsageStats{
        return AppUsageStats()
    }

    @Provides
    @Singleton
    fun provideVPNServiceActionRequest(): MutableLiveData<VPN_SERVICE_ACTION> {
        return vpnServiceActionRequest
    }

    @Provides
    @Singleton
    fun provideVPNServiceStatus(): MutableLiveData<VPN_SERVICE_STATUS> {
        return vpnServiceStatus
    }

    @Singleton
    @Provides
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    fun provideFlowDao(appDatabase: AppDatabase): FlowDao {
        return appDatabase.FlowDao()
    }

    @Provides
    fun provideLANShieldSessionDao(appDatabase: AppDatabase): LANShieldSessionDao {
        return appDatabase.LANShieldSessionDao()
    }

    @Provides
    fun provideLanAccessPolicyDao(appDatabase: AppDatabase): LanAccessPolicyDao {
        return appDatabase.LanAccessPolicyDao()
    }

    @Provides
    fun provideOpenPortsDao(appDatabase: AppDatabase): OpenPortsDao {
        return appDatabase.OpenPortsDao()
    }

    @Singleton
    @Provides
    fun provideVPNNotificationManager(@ApplicationContext context: Context): LANShieldNotificationManager {
        return LANShieldNotificationManager(context)
    }

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext appContext: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = {
                appContext.preferencesDataStoreFile(PREFERENCES_STORE_NAME)
            }
        )
}
