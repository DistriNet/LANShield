package org.distrinet.lanshield.vpnservice

data class DpiResult(var jsonBuffer: String = "", var protocolName: String = "")

class dpi {

    companion object {
        init {
            System.loadLibrary("lanshield-dpi")
        }

        external fun doDPI(packet: ByteArray, packetSize: Int, packetOffset : Int, dpiResult: DpiResult): Int

        external fun terminateNDPI()
    }


}