package org.distrinet.lanshield.backendsync

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import org.distrinet.lanshield.packageNameIsSystem
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar


class AppUsageStats {

    companion object {
        fun toJSONArray(
            appUsageStats: List<UsageStats>,
            context: Context
        ): JSONArray {

            val jsonUsageArray = JSONArray()

            for (usageStat in appUsageStats) {
                jsonUsageArray.put(usageStatToJSON(usageStat, context))
            }
            return jsonUsageArray
        }

        private fun usageStatToJSON(
            usageStat: UsageStats,
            context: Context
        ): JSONObject {
            val json = JSONObject()
            json.put("app_name", usageStat.packageName)
            json.put("begin_time", usageStat.firstTimeStamp)
            json.put("end_time", usageStat.lastTimeStamp)
            json.put("time_used", usageStat.totalTimeInForeground)
            json.put("time_visible", usageStat.totalTimeVisible)
            json.put(
                "is_system",
                packageNameIsSystem(usageStat.packageName, context.packageManager)
            )


            return json
        }
    }

    fun getUsageStatsList(context: Context, timeOfLastSync: Long): List<UsageStats> {
        val usm = getUsageStatsManager(context)
        val calendar: Calendar = Calendar.getInstance()
        val endTime: Long = calendar.timeInMillis

        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, timeOfLastSync, endTime)
    }

    private fun getUsageStatsManager(context: Context): UsageStatsManager {
        return context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }
}