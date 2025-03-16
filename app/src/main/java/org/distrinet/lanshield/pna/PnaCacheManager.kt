package org.distrinet.lanshield.pna

import android.util.Log

class PnaCacheManager {

    companion object {
        const val TIMEOUT = 5 * 60 * 1000L  // 5 minutes in ms
    }

    private val allowedCache = mutableMapOf<String, Long>()
    private val deniedCache = mutableMapOf<String, Long>()

    fun isPreflightValid(key: String): Boolean {
        val lastTime = allowedCache[key]
        return if (lastTime != null) {
            val elapsed = System.currentTimeMillis() - lastTime
            if (elapsed < TIMEOUT) {
                Log.d("PnaCacheManager", "Cache hit for $key: $elapsed ms elapsed (< $TIMEOUT).")
                true
            } else {
                Log.d("PnaCacheManager", "Cache expired for $key: $elapsed ms elapsed (>= $TIMEOUT). Removing entry.")
                allowedCache.remove(key)
                false
            }
        } else {
            Log.d("PnaCacheManager", "No allowed cache entry for $key.")
            false
        }
    }

    fun shouldRetryDenied(key: String): Boolean {
        val lastTime = deniedCache[key]
        return if (lastTime != null) {
            val elapsed = System.currentTimeMillis() - lastTime
            if (elapsed < TIMEOUT) {
                Log.d("PnaCacheManager", "Denied cache hit for $key: $elapsed ms elapsed (< $TIMEOUT).")
                false
            } else {
                Log.d("PnaCacheManager", "Denied cache expired for $key: $elapsed ms elapsed (>= $TIMEOUT). Removing entry.")
                deniedCache.remove(key)
                true
            }
        } else {
            true
        }
    }

    fun updateAllowed(key: String) {
        allowedCache[key] = System.currentTimeMillis()
        Log.d("PnaCacheManager", "PNA Allowed for $key - Caching for 5 min")
    }

    fun updateDenied(key: String) {
        deniedCache[key] = System.currentTimeMillis()
        Log.d("PnaCacheManager", "PNA Denied for $key - Caching for 5 min")
    }
}