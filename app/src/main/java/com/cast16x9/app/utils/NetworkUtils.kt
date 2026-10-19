package com.cast16x9.app.utils

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    /**
     * Finds the primary IPv4 address of the device on Wi-Fi or Ethernet.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        // Prioritize standard local subnet IPs
                        if (hostAddress.startsWith("192.168.") ||
                            hostAddress.startsWith("10.") ||
                            hostAddress.startsWith("172.")) {
                            return hostAddress
                        }
                    }
                }
            }
            // Fallback: any non-loopback IPv4
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    const val DEFAULT_HTTP_PORT = 8080
    const val DEFAULT_WS_PORT = 8088
}
