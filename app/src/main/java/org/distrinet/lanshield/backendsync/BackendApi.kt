package org.distrinet.lanshield.backendsync

import android.util.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.distrinet.lanshield.BACKEND_URL
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackendApi @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** POST a fully-materialised JSON body. Use for small payloads. */
    suspend fun postJson(endpoint: String, body: JSONObject): JSONObject =
        post(endpoint, body.toString().toRequestBody(JSON_MEDIA_TYPE))

    /**
     * POST a JSON body written on the fly. [writeBody] receives a [JsonWriter] streaming directly
     * to the request socket, so the caller can serialise an arbitrarily large batch one item at a
     * time without holding the whole body in memory.
     */
    suspend fun postStreaming(endpoint: String, writeBody: (JsonWriter) -> Unit): JSONObject =
        post(endpoint, streamingBody(writeBody))

    private suspend fun post(endpoint: String, body: RequestBody): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(BACKEND_URL + endpoint)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} for $endpoint: $responseBody")
                }
                if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
            }
        }

    private fun streamingBody(writeBody: (JsonWriter) -> Unit): RequestBody =
        object : RequestBody() {
            override fun contentType() = JSON_MEDIA_TYPE

            override fun writeTo(sink: BufferedSink) {
                JsonWriter(OutputStreamWriter(sink.outputStream(), StandardCharsets.UTF_8))
                    .use { writer -> writeBody(writer) }
            }
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
