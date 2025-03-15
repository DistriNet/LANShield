package org.distrinet.lanshield.pna

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.util.Log
import org.distrinet.lanshield.vpnservice.LANShieldNotificationManager

class PnaManager(private val vpnNotificationManager: LANShieldNotificationManager) {

    companion object {
        val pnaCacheManager = PnaCacheManager()
    }

    private fun extractServerPort(urlStr: String): String {
        return try {
            val url = URL(urlStr)
            val port = if (url.port == -1) {
                if (url.protocol.lowercase(Locale.ROOT) == "https") 443 else 80
            } else {
                url.port
            }
            "${url.host}:$port"
        } catch (e: Exception) {
            Log.e("PnaManager", "Error extracting server port: ${e.message}")
            urlStr
        }
    }


    fun sendPreflightRequest(urlStr: String): Boolean {
        val serverPortKey = extractServerPort(urlStr)
        Log.d("PnaManager", "Checking cache for key: $serverPortKey")

        if (pnaCacheManager.isPreflightValid(serverPortKey)) {
            Log.d("PnaManager", "PNA Cached as ALLOWED for $serverPortKey, skipping preflight.")
            return true
        }
        if (!pnaCacheManager.shouldRetryDisabled(serverPortKey)) {
            Log.d("PnaManager", "PNA Cached as DENIED for $serverPortKey, skipping preflight.")
            return false
        }

        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "OPTIONS"
            connection.setRequestProperty("Access-Control-Request-Private-Network", "true")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doInput = true

            connection.connect()
            connection.inputStream?.use { /* Consume stream if any, even if empty */ }
            val responseCode = connection.responseCode
            val allowHeader = connection.getHeaderField("Access-Control-Allow-Private-Network")
            connection.disconnect()

            Log.d("PnaManager", "Preflight response from $serverPortKey: $responseCode, header: $allowHeader")

            if (responseCode == 200 && allowHeader != null && allowHeader.lowercase(Locale.ROOT) == "true") {
                pnaCacheManager.updateEnabled(serverPortKey)
                vpnNotificationManager.postPreflightSuccessNotification("Preflight successful for $serverPortKey (cached for 5 min)")
                true
            } else {
                pnaCacheManager.updateDisabled(serverPortKey)
                vpnNotificationManager.postPreflightFailureNotification("Preflight failed for $serverPortKey (cached for 30 min)")
                false
            }
        } catch (e: Exception) {
            Log.e("PnaManager", "Error sending preflight request: ${e.message}")
            pnaCacheManager.updateDisabled(serverPortKey)
            vpnNotificationManager.postPreflightFailureNotification("Preflight failed for $serverPortKey (cached for 30 min)")
            false
        }
    }
}