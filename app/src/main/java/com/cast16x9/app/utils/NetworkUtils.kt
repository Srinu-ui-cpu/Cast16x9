package com.cast16x9.app.utils

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    const val DEFAULT_HTTP_PORT = 8080

    /**
     * Accurately finds the Wi-Fi IPv4 address of the device.
     * Prioritizes Wi-Fi (wlan) and Ethernet interfaces over cellular networks (rmnet).
     */
    fun getLocalIpAddress(context: Context? = null): String {
        // Method 1: Check WifiManager directly if context is available
        if (context != null) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    val ip = String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                    if (ip != "0.0.0.0") {
                        return ip
                    }
                }
            } catch (e: Exception) {
                // Fall back to network interfaces inspection
            }
        }

        // Method 2: Scan network interfaces specifically prioritizing Wi-Fi (wlan*, eth*)
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // Priority 1: wlan interfaces (standard Wi-Fi on Android)
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                    val ip = extractValidIpv4(intf)
                    if (ip != null) return ip
                }
            }

            // Priority 2: Any private subnet IP (192.168.x.x or 172.16-31.x.x), excluding cellular interfaces
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                // Explicitly skip cellular and virtual interfaces
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("dummy") || name.startsWith("tun") ||
                    name.startsWith("p2p") || name.startsWith("lo")) {
                    continue
                }
                val ip = extractValidIpv4(intf)
                if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("172."))) {
                    return ip
                }
            }

            // Priority 3: Any non-cellular private IPv4
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("dummy") || name.startsWith("lo")) {
                    continue
                }
                val ip = extractValidIpv4(intf)
                if (ip != null) return ip
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return "127.0.0.1"
    }

    private fun extractValidIpv4(intf: NetworkInterface): String? {
        try {
            if (!intf.isUp || intf.isLoopback) return null
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val host = addr.hostAddress ?: continue
                    if (host != "0.0.0.0" && host != "127.0.0.1") {
                        return host
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    /**
     * Returns a list of all active non-cellular IP addresses for troubleshooting.
     */
    fun getAllReachableIps(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("dummy") || name.startsWith("lo")) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!list.contains(host)) list.add(host)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
