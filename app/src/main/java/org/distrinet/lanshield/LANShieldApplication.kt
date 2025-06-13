package org.distrinet.lanshield

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.MutableLiveData
import androidx.work.Configuration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.distrinet.lanshield.database.AppDatabase
import org.distrinet.lanshield.database.dao.FlowDao
import org.distrinet.lanshield.database.dao.LANShieldSessionDao
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.vpnservice.LANShieldNotificationManager
import javax.inject.Inject
import javax.inject.Singleton

const val FEEDBACK_URL = "https://forms.gle/zh2J3KMzZjMnv2Qi9"
const val ABOUT_LANSHIELD_URL = "https://lanshield.eu"

val NO_SYSTEM_OVERRIDE_PACKAGE_NAMES = listOf("com.android.chrome")

const val PACKAGE_NAME_UNKNOWN = "Unknown"
const val PACKAGE_NAME_ROOT = "Root"
const val PACKAGE_NAME_SYSTEM = "System"


val RESERVED_PACKAGE_NAMES = listOf(PACKAGE_NAME_UNKNOWN, PACKAGE_NAME_ROOT, PACKAGE_NAME_SYSTEM)

const val PREFERENCES_STORE_NAME = "LANSHIELD_DATASTORE"

val INTRO_COMPLETED_KEY = booleanPreferencesKey("intro_completed")
val DEFAULT_POLICY_KEY = stringPreferencesKey("default_policy")
val SYSTEM_APPS_POLICY_KEY = stringPreferencesKey("block_system_apps_policy")

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

fun packageNameIsSystem(packageName: String,context: Context): Boolean {
    val applicationMetadata = getPackageMetadata(packageName, context)
    return applicationMetadata.isSystem
}

private val packageMetadataCache = mutableMapOf<String, PackageMetadata>()

fun getPackageMetadata(packageName: String, context: Context) : PackageMetadata {
    return packageMetadataCache.getOrPut(packageName) {
        lookupPackageMetadata(packageName, context)
    }
}

private fun lookupPackageMetadata(packageName: String, context: Context) : PackageMetadata {
    if(packageName.contentEquals(PACKAGE_NAME_UNKNOWN)) return PackageMetadata(packageName, PACKAGE_NAME_UNKNOWN, false)
    if(packageName.contentEquals(PACKAGE_NAME_ROOT)) return PackageMetadata(packageName, PACKAGE_NAME_ROOT, true)
    if(packageName.contentEquals(PACKAGE_NAME_SYSTEM)) return PackageMetadata(packageName, PACKAGE_NAME_SYSTEM, true)

    try {
        val applicationInfo =
            context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val label = context.packageManager.getApplicationLabel(applicationInfo).toString()
        val isSystem = applicationInfoIsSystem(applicationInfo)
        return PackageMetadata(packageName, label, isSystem)
    } catch (e: PackageManager.NameNotFoundException) {
        return PackageMetadata(packageName, packageName, false)
    }
}

fun getPackageNameFromUid(appUid: Int, packageManager: PackageManager): String {
    when (appUid) {
        0 -> return PACKAGE_NAME_ROOT
        -1 -> return PACKAGE_NAME_UNKNOWN
        1000 -> return PACKAGE_NAME_SYSTEM
    }

    val appPackageName : String
    val allPackages = packageManager.getPackagesForUid(appUid)
    if(allPackages != null && allPackages.size == 1) {
        appPackageName = allPackages[0]
    }
    else {
        val sharedUidPackageName = packageManager.getNameForUid(appUid)
        appPackageName = sharedUidPackageName ?: PACKAGE_NAME_UNKNOWN
    }
    return appPackageName
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
class LANShieldApplication() : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .build()
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
