package com.cast16x9.app.dlna

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class DlnaManager(private val context: Context) {

    val discoveredDevices = CopyOnWriteArrayList<DlnaDevice>()
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Discovers DLNA/UPnP Smart TVs on the local Wi-Fi network via SSDP M-SEARCH.
     */
    suspend fun discoverDevices(): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<DlnaDevice>()
        var socket: DatagramSocket? = null

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("cast16x9_ssdp_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            val ssdpQuery = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n"

            val queryBytes = ssdpQuery.toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            socket = DatagramSocket()
            socket.soTimeout = 2500

            val packet = DatagramPacket(queryBytes, queryBytes.size, group, 1900)
            socket.send(packet)

            val buffer = ByteArray(2048)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 3000) {
                try {
                    val receivePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length)

                    val location = extractHeader(response, "LOCATION")
                    if (!location.isNullOrEmpty() && devices.none { it.location == location }) {
                        val parsed = fetchDeviceDetails(location)
                        if (parsed != null && devices.none { it.udn == parsed.udn }) {
                            devices.add(parsed)
                        }
                    }
                } catch (e: Exception) {
                    // Timeout reached or packet read ended
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket?.close()
            try {
                multicastLock?.release()
            } catch (e: Exception) {}
        }

        discoveredDevices.clear()
        discoveredDevices.addAll(devices)
        devices
    }

    private fun extractHeader(response: String, headerName: String): String? {
        val lines = response.split("\r\n")
        for (line in lines) {
            if (line.startsWith(headerName, ignoreCase = true)) {
                val idx = line.indexOf(':')
                if (idx != -1) {
                    return line.substring(idx + 1).trim()
                }
            }
        }
        return null
    }

    private fun fetchDeviceDetails(locationUrl: String): DlnaDevice? {
        return try {
            val url = URL(locationUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"

            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            val friendlyName = extractTag(xml, "friendlyName") ?: "Smart TV"
            val udn = extractTag(xml, "UDN") ?: locationUrl

            // Extract AVTransport controlURL
            val controlUrlRelative = extractControlUrl(xml) ?: "/AVTransport/control"
            val controlUrl = if (controlUrlRelative.startsWith("http")) {
                controlUrlRelative
            } else {
                val base = "${url.protocol}://${url.host}:${url.port}"
                if (controlUrlRelative.startsWith("/")) "$base$controlUrlRelative" else "$base/$controlUrlRelative"
            }

            DlnaDevice(friendlyName, locationUrl, controlUrl, udn)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTag(xml: String, tagName: String): String? {
        val open = "<$tagName>"
        val close = "</$tagName>"
        val start = xml.indexOf(open)
        if (start != -1) {
            val end = xml.indexOf(close, start + open.length)
            if (end != -1) {
                return xml.substring(start + open.length, end).trim()
            }
        }
        return null
    }

    private fun extractControlUrl(xml: String): String? {
        val avTransportIdx = xml.indexOf("urn:schemas-upnp-org:service:AVTransport:1")
        if (avTransportIdx != -1) {
            val controlTag = "<controlURL>"
            val start = xml.indexOf(controlTag, avTransportIdx)
            if (start != -1) {
                val end = xml.indexOf("</controlURL>", start + controlTag.length)
                if (end != -1) {
                    return xml.substring(start + controlTag.length, end).trim()
                }
            }
        }
        return null
    }

    /**
     * Commands the DLNA TV to play a video/photo directly via UPnP SOAP call with dynamic MIME type.
     */
    suspend fun playMediaOnDlnaTv(
        device: DlnaDevice,
        mediaUrl: String,
        title: String,
        mimeType: String = "video/mp4"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val upnpClass = when {
                mimeType.startsWith("video/") -> "object.item.videoItem.movie"
                mimeType.startsWith("audio/") -> "object.item.audioItem.musicTrack"
                else -> "object.item.imageItem.photo"
            }

            // Step 1: SetAVTransportURI with explicit UPnP class for TV audio decoder activation
            val setUriSoap = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                            <InstanceID>0</InstanceID>
                            <CurrentURI>$mediaUrl</CurrentURI>
                            <CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="0" parentID="-1" restricted="1"&gt;&lt;dc:title&gt;$title&lt;/dc:title&gt;&lt;upnp:class&gt;$upnpClass&lt;/upnp:class&gt;&lt;res protocolInfo="http-get:*:$mimeType:*"&gt;$mediaUrl&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
                        </u:SetAVTransportURI>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            sendSoapAction(device.controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", setUriSoap)

            // Step 2: Play
            val playSoap = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                            <InstanceID>0</InstanceID>
                            <Speed>1</Speed>
                        </u:Play>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            sendSoapAction(device.controlUrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", playSoap)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun sendSoapAction(controlUrl: String, action: String, xmlPayload: String) {
        val url = URL(controlUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPAction", "\"$action\"")

        OutputStreamWriter(conn.outputStream, "UTF-8").use {
            it.write(xmlPayload)
            it.flush()
        }

        val respCode = conn.responseCode
        if (respCode >= 400) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
            println("DLNA SOAP Error ($respCode): $err")
        }
    }
}
