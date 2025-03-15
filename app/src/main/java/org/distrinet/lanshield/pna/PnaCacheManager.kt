package org.distrinet.lanshield.pna

import android.util.Log

class PnaCacheManager {

    private companion object {
        const val ENABLED_TIMEOUT = 5 * 60 * 1000L       // 5 minutes in ms
        const val DISABLED_RETRY_INTERVAL = 30 * 60 * 1000L // 30 minutes in ms
    }

    private val enabledCache = mutableMapOf<String, Long>()
    private val disabledCache = mutableMapOf<String, Long>()

    fun isPreflightValid(serverPortKey: String): Boolean {
        val lastTime = enabledCache[serverPortKey]
        return if (lastTime != null) {
            val elapsed = System.currentTimeMillis() - lastTime
            if (elapsed < ENABLED_TIMEOUT) {
                Log.d("PnaCacheManager", "Cache hit for $serverPortKey: $elapsed ms elapsed (< $ENABLED_TIMEOUT).")
                true
            } else {
                Log.d("PnaCacheManager", "Cache expired for $serverPortKey: $elapsed ms elapsed (>= $ENABLED_TIMEOUT). Removing entry.")
                enabledCache.remove(serverPortKey)
                false
            }
        } else {
            Log.d("PnaCacheManager", "No cache entry for $serverPortKey.")
            false
        }
    }

    fun shouldRetryDisabled(serverPortKey: String): Boolean {
        val lastTime = disabledCache[serverPortKey]
        return if (lastTime != null) {
            val elapsed = System.currentTimeMillis() - lastTime
            if (elapsed < DISABLED_RETRY_INTERVAL) {
                Log.d("PnaCacheManager", "Disabled cache hit for $serverPortKey: $elapsed ms elapsed (< $DISABLED_RETRY_INTERVAL).")
                false
            } else {
                Log.d("PnaCacheManager", "Disabled cache expired for $serverPortKey: $elapsed ms elapsed (>= $DISABLED_RETRY_INTERVAL). Removing entry.")
                disabledCache.remove(serverPortKey)
                true
            }
        } else {
            true
        }
    }

    fun updateEnabled(serverPortKey: String) {
        enabledCache[serverPortKey] = System.currentTimeMillis()
        Log.d("PnaCacheManager", "PNA Allowed for $serverPortKey - Caching for 5 min")
    }

    fun updateDisabled(serverPortKey: String) {
        disabledCache[serverPortKey] = System.currentTimeMillis()
        Log.d("PnaCacheManager", "PNA Denied for $serverPortKey - Caching for 30 min")
    }
}