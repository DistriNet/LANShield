package org.distrinet.lanshield.ui.lantraffic

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.JsonWriter
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.distrinet.lanshield.database.dao.FlowDao
import org.distrinet.lanshield.database.model.LANFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import javax.inject.Inject

@HiltViewModel
class LANTrafficViewModel @Inject constructor(val flowDao: FlowDao) : ViewModel() {

    val liveFlowAverages = flowDao.getFlowAverages(0)

    fun exportFlows(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileToShare = File(context.cacheDir, "lanshield_export_${System.currentTimeMillis()}.json")

            val pageSize = 1000
            var offset = 0
            var isFinished = false

            FileOutputStream(fileToShare).use { fos ->
                val writer = JsonWriter(OutputStreamWriter(fos, "UTF-8"))
                writer.setIndent("  ")

                writer.beginObject()
                writer.name("device_sdk").value(Build.VERSION.SDK_INT)
                writer.name("model").value(Build.MODEL)

                writer.name("flows")
                writer.beginArray()

                while (!isFinished) {
                    val batch = flowDao.getFlowsPaged(limit = pageSize, offset = offset)

                    if (batch.isEmpty()) {
                        isFinished = true
                    } else {
                        for (flow in batch) {
                            val jsonObject = flow.toJSON()
                            writeJsonObjectToStream(writer, jsonObject)
                        }

                        offset += pageSize
                        writer.flush()
                    }
                }

                writer.endArray()
                writer.endObject()
                writer.close()
            }

            withContext(Dispatchers.Main) {
                shareFile(context, fileToShare)
            }
        }
    }

    private fun writeJsonObjectToStream(writer: JsonWriter, jsonObject: JSONObject) {
        writer.beginObject()

        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)

            writer.name(key)

            when (value) {
                is String -> writer.value(value)
                is Int -> writer.value(value.toLong())
                is Long -> writer.value(value)
                is Double -> writer.value(value)
                is Boolean -> writer.value(value)
                is JSONObject -> writeJsonObjectToStream(writer, value)
                else -> writer.value(value.toString())
            }
        }
        writer.endObject()
    }

    private fun shareFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/json"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Export Flows"))
    }
}