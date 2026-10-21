package com.cast16x9.app.server

import android.content.Context
import android.net.Uri
import com.cast16x9.app.model.SharedFile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLConnection
import java.util.concurrent.CopyOnWriteArrayList

class WebReceiverServer(
    private val context: Context,
    port: Int = 8080
) : NanoWSD(port) {

    // Active WebSocket connections (TV browsers)
    private val connectedClients = CopyOnWriteArrayList<ScreenWebSocket>()

    // Shared files exposed to the TV Web Receiver
    val sharedFiles = CopyOnWriteArrayList<SharedFile>()

    // Currently active cast media
    var currentMediaUri: Uri? = null
    var currentMediaMimeType: String? = null
    var currentMediaTitle: String? = null

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return ScreenWebSocket(handshake)
    }

    override fun serve(session: IHTTPSession): Response {
        // Handle WebSocket handshake if requested
        if (isWebsocketRequested(session)) {
            return super.serve(session)
        }

        val uri = session.uri
        val method = session.method

        try {
            when {
                // 1. Root & Web Receiver Assets
                uri == "/" || uri == "/index.html" -> {
                    return serveAsset("web/index.html", "text/html")
                }
                uri == "/style.css" -> {
                    return serveAsset("web/style.css", "text/css")
                }
                uri == "/app.js" -> {
                    return serveAsset("web/app.js", "application/javascript")
                }

                // 2. API: List Shared Files
                uri == "/api/files" -> {
                    return serveFilesJson()
                }

                // 3. File Download / Stream
                uri.startsWith("/files/download/") -> {
                    val fileId = uri.removePrefix("/files/download/")
                    val file = sharedFiles.find { it.id == fileId }
                    if (file != null) {
                        return serveStreamableMedia(file.uri, file.mimeType, session, file.name, isAttachment = true)
                    }
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
                }

                // 4. Active Casted Media Stream (Video / Photo)
                uri.startsWith("/media/active") -> {
                    val activeUri = currentMediaUri
                    val mime = currentMediaMimeType ?: "video/mp4"
                    if (activeUri != null) {
                        return serveStreamableMedia(activeUri, mime, session, currentMediaTitle)
                    }
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No active media")
                }

                else -> {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    /**
     * Serves an asset file from assets/web/
     */
    private fun serveAsset(assetPath: String, mimeType: String): Response {
        val inputStream = context.assets.open(assetPath)
        return newChunkedResponse(Response.Status.OK, mimeType, inputStream)
    }

    /**
     * Serves the list of shared files as a JSON array.
     */
    private fun serveFilesJson(): Response {
        val jsonArray = JSONArray()
        for (f in sharedFiles) {
            val obj = JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("size", f.size)
                put("mimeType", f.mimeType)
                put("isVideo", f.isVideo)
                put("isPhoto", f.isPhoto)
                put("url", "/files/download/${f.id}")
            }
            jsonArray.put(obj)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
    }

    /**
     * Serves media with HTTP Range support (206 Partial Content) for smooth TV video seeking.
     */
    private fun serveStreamableMedia(
        contentUri: Uri,
        mimeType: String,
        session: IHTTPSession,
        filename: String?,
        isAttachment: Boolean = false
    ): Response {
        val cr = context.contentResolver
        val fileLength = cr.openAssetFileDescriptor(contentUri, "r")?.use { it.length } ?: -1L
        val dispositionType = if (isAttachment) "attachment" else "inline"

        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileLength > 0) {
            var start: Long = 0
            var end: Long = fileLength - 1
            val ranges = rangeHeader.removePrefix("bytes=").split("-")

            if (ranges[0].isNotEmpty()) {
                start = ranges[0].toLong()
            }
            if (ranges.size > 1 && ranges[1].isNotEmpty()) {
                end = ranges[1].toLong()
            }
            if (end >= fileLength) {
                end = fileLength - 1
            }

            val contentLength = end - start + 1
            val stream = cr.openInputStream(contentUri) ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Could not open stream"
            )
            stream.skip(start)

            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                stream,
                contentLength
            )
            response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
            response.addHeader("Accept-Ranges", "bytes")
            if (filename != null) {
                response.addHeader("Content-Disposition", "$dispositionType; filename=\"$filename\"")
            }
            return response
        }

        // Full content response
        val fullStream = cr.openInputStream(contentUri) ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Could not open stream"
        )
        val response = if (fileLength > 0) {
            newFixedLengthResponse(Response.Status.OK, mimeType, fullStream, fileLength)
        } else {
            newChunkedResponse(Response.Status.OK, mimeType, fullStream)
        }
        response.addHeader("Accept-Ranges", "bytes")
        if (filename != null) {
            response.addHeader("Content-Disposition", "$dispositionType; filename=\"$filename\"")
        }
        return response
    }

    /**
     * Broadcasts a binary screen frame to all connected TV Web Receivers.
     */
    fun broadcastScreenFrame(frameData: ByteArray) {
        val deadClients = mutableListOf<ScreenWebSocket>()
        for (client in connectedClients) {
            try {
                if (client.isOpen) {
                    client.send(frameData)
                } else {
                    deadClients.add(client)
                }
            } catch (e: Exception) {
                deadClients.add(client)
            }
        }
        connectedClients.removeAll(deadClients)
    }

    /**
     * Sends a remote JSON command to the TV Web Receiver (e.g. aspect ratio, video play).
     */
    fun sendControlCommand(commandJson: JSONObject) {
        val payload = commandJson.toString()
        for (client in connectedClients) {
            try {
                if (client.isOpen) {
                    client.send(payload)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Casts a local video/photo directly to the TV Web Receiver.
     */
    fun castMediaToTv(uri: Uri, mimeType: String, title: String) {
        currentMediaUri = uri
        currentMediaMimeType = mimeType
        currentMediaTitle = title

        val isVideo = mimeType.startsWith("video/")
        val json = JSONObject().apply {
            put("action", if (isVideo) "play_video" else "show_photo")
            put("url", "/media/active")
            put("title", title)
        }
        sendControlCommand(json)
    }

    /**
     * Switches the 16:9 aspect ratio mode on connected TVs.
     * @param mode "cover" (16:9 zoom no bars), "stretch" (16:9 fill no bars), "fit" (contain)
     */
    fun setTvAspectMode(mode: String) {
        val json = JSONObject().apply {
            put("action", "set_aspect")
            put("mode", mode)
        }
        sendControlCommand(json)
    }

    inner class ScreenWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            connectedClients.add(this)
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            connectedClients.remove(this)
        }

        override fun onMessage(message: WebSocketFrame?) {
            // Can receive commands from TV remote
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: java.io.IOException?) {
            connectedClients.remove(this)
        }
    }

    fun getConnectedClientCount(): Int = connectedClients.size
}
