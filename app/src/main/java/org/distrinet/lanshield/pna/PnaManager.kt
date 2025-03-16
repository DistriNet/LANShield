package org.distrinet.lanshield.pna

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.util.Log

class PnaManager() {

    companion object {
        val pnaCacheManager = PnaCacheManager()
    }

    private fun buildKey(ip: String, port: Int): String = "$ip:$port"

    fun sendPreflightRequest(ip: String, port: Int): Boolean {
        val key = buildKey(ip, port)
        Log.d("PnaManager", "Checking cache for key: $key")

        if (pnaCacheManager.isPreflightValid(key)) {
            Log.d("PnaManager", "PNA Cached as ALLOWED for $key, skipping preflight.")
            return true
        }
        if (!pnaCacheManager.shouldRetryDenied(key)) {
            Log.d("PnaManager", "PNA Cached as DENIED for $key, skipping preflight.")
            return false
        }

        if (port !in listOf(80, 8080, 8081)) {
            Log.d("PnaManager", "Port $port not in allowed list. Skipping preflight.")
            return false
        }

        val destinationUrl = "http://$ip:$port/"
        Log.d("PnaManager", "Sending preflight request to $destinationUrl")
        return try {
            val url = URL(destinationUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "OPTIONS"
            connection.setRequestProperty("Access-Control-Request-Private-Network", "true")
            connection.setRequestProperty("Connection", "close")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doInput = true

            connection.connect()
            // Try to consume the input stream. If that fails, try the error stream.
            val stream = connection.inputStream ?: connection.errorStream
            stream?.use { }  // Consume response, even if empty.
            val responseCode = connection.responseCode
            val allowHeader = connection.getHeaderField("Access-Control-Allow-Private-Network")
            connection.disconnect()

            Log.d("PnaManager", "Preflight response from $key: $responseCode, header: $allowHeader")

            if (responseCode == 200 && allowHeader?.lowercase(Locale.ROOT) == "true") {
                pnaCacheManager.updateAllowed(key)
                true
            } else {
                pnaCacheManager.updateDenied(key)
                false
            }
        } catch (e: Exception) {
            Log.e("PnaManager", "Error sending preflight request: ${e.message}")
            pnaCacheManager.updateDenied(key)
            false
        }
    }
}