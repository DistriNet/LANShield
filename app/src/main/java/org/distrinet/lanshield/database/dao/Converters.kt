package org.distrinet.lanshield.database.dao

import androidx.room.TypeConverter
import java.net.InetSocketAddress
import java.util.UUID

class StringListConverter {
    @TypeConverter
    fun fromStringList(list: List<String>?): String? {
        // Convert the list of strings to a single comma-separated string
        return list?.joinToString(separator = ",")
    }

    @TypeConverter
    fun toStringList(data: String?): List<String>? {
        // Convert the comma-separated string back into a list of strings
        return data?.split(",")?.map { it.trim() }?.toList()
    }
}
class StringUUIDConverter{
    @TypeConverter
    fun fromStringToUUID(data: String?): UUID?{
        return UUID.fromString(data)
    }
    @TypeConverter
    fun fromUUIDToString(data: UUID?): String {
        return data.toString()
    }
}
class InetSocketAddressConverter {
    @TypeConverter
    fun fromInetSocketAddress(address: InetSocketAddress?): String? {
        if (address == null) return null
        // Combine the host name (or IP) and port into a single string
        return "${address.address.hostAddress}:${address.port}"
    }

    @TypeConverter
    fun toInetSocketAddress(address: String?): InetSocketAddress? {
        if (address == null) return null
        // Split the string back into the host and port components
        val colonIndex = address.lastIndexOf(':')
        if(colonIndex == -1) return null


        return try {
            val host = address.substring(0, colonIndex)
            val port = address.substring(colonIndex+1).toInt()
            InetSocketAddress(host, port)
        } catch (e: NumberFormatException) {
            // Handle the case where the port is not a valid integer
            null
        }
    }
}