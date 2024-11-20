package org.distrinet.lanshield.vpnservice

import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.experimental.and

class IPHeader(packetBuffer: ByteBuffer) {
    lateinit var source: InetSocketAddress
    lateinit var destination: InetSocketAddress
    private var protocol: Int = 0
    private val ipVersion: Int
    var size = 0
    private var canDoDpi = false

    init {
        require(packetBuffer.limit() >= 24)
        ipVersion = packetBuffer.get(0).and(0xf0.toByte()).toInt().ushr(4)

        when (ipVersion) {
            4 -> {
                parseIPHeader(packetBuffer, 4, 12, 9, 20, 4)
            }

            6 -> {
                require(packetBuffer.limit() >= 44)
                parseIPHeader(packetBuffer, 16, 8, 6, 40, 6)
            }

            else -> {
                throw IllegalArgumentException("Invalid IP version: $ipVersion")
            }
        }
    }

    public fun hasPayloadForDpi(): Boolean {
        return canDoDpi
    }

    private fun parseInetAddress(rawPacket: ByteBuffer, addressSize: Int): InetAddress {
        val addressBytes = ByteArray(addressSize)
        rawPacket.get(addressBytes, 0, addressSize)
        return InetAddress.getByAddress(addressBytes)
    }

    private fun parseIPHeader(
        rawPacket: ByteBuffer,
        addressSize: Int,
        sourceOffset: Int,
        protocolOffset: Int,
        sourcePortOffset: Int,
        version: Int
    ) {
        rawPacket.position(protocolOffset)
        protocol = rawPacket.get().toInt() and 0xFF

        rawPacket.position(sourceOffset)
        val sourceIp = parseInetAddress(rawPacket, addressSize)
        val destinationIp = parseInetAddress(rawPacket, addressSize)

        size = if (version == 4) {
            val totalLengthOffset = 2
            rawPacket.getShort(totalLengthOffset).toInt()
        } else {
            val payloadLengthOffset = 4
            val payloadLength = rawPacket.getShort(payloadLengthOffset).toInt() and 0xFFFF
            val ipv6HeaderLength = 40 // IPv6 header is always 40 bytes
            payloadLength + ipv6HeaderLength
        }


        var sourcePort = 0
        var destinationPort = 0
        if (protocol == 6 || protocol == 17) { // TCP or UDP
            rawPacket.position(sourcePortOffset)
            sourcePort = rawPacket.short.toInt() and 0xFFFF
            destinationPort = rawPacket.short.toInt() and 0xFFFF
        }

        source = InetSocketAddress(sourceIp, sourcePort)
        destination = InetSocketAddress(destinationIp, destinationPort)

        if (protocol == 6) { //TCP
            val ipHeaderLength = if (version == 4) {
                rawPacket.get(0).toInt().and(0x0F) * 4
            } else {
                40
            }
            val dataOffsetAndNs: Int = rawPacket.get(ipHeaderLength + 12).toInt()
            val tcpHeaderLength = dataOffsetAndNs.and(0xF0).shr(4) * 4

            canDoDpi = ipHeaderLength + tcpHeaderLength < size
        }
        else {
            canDoDpi = true
        }

    }

    fun protocolNumberAsString(): String {
        return when (protocol) {
            0 -> "Hop-by-Hop Options Header"
            1 -> "ICMPv4"
            6 -> "TCP"
            17 -> "UDP"
            41 -> "IPv6 encapsulation"
            47 -> "GRE"
            50 -> "ESP"
            51 -> "AH"
            58 -> "ICMPv6"
            59 -> "No Next Header for IPv6"
            60 -> "Destination Options for IPv6"
            88 -> "EIGRP"
            89 -> "OSPF"
            115 -> "L2TP"
            413 -> "Segment Routing over IPv6"
            else -> "Unknown: $protocol"
        }
    }

    fun ipVersion(): Int {
        return ipVersion
    }

    fun protocolNumberAsOSConstant(): Int {
        return when (protocol) {
            0 -> IPPROTO_HOPOPTS
            1 -> OsConstants.IPPROTO_ICMP
            6 -> OsConstants.IPPROTO_TCP
            17 -> OsConstants.IPPROTO_UDP
            58 -> OsConstants.IPPROTO_ICMPV6
            else -> 0
        }
    }

    override fun toString(): String {
        return "PacketHeader(source=$source, destination=$destination, protocol=${protocolNumberAsString()})"
    }

    fun toCSVLine(packageName: String?): String {
        val isoDate = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)
        return "$isoDate;$packageName;$protocol;${protocolNumberAsString()};${source.address};${source.port};${destination.address};${destination.port};"
    }

    companion object {
        fun csvHeader(): String {
            return "date;packageName;transportProto;transportProtoStr;sourceIP;sourcePort;destinationIP;destinationPort;"
        }

        public val IPPROTO_HOPOPTS = 0
    }
}