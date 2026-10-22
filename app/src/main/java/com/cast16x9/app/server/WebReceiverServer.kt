package com.cast16x9.app.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cast16x9.app.model.SharedFile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList

class WebReceiverServer(
    private val context: Context,
    port: Int = 8080
) : NanoWSD(port) {

    // Connected TV Web Receiver WebSocket clients (used for signaling and remote control)
    private val connectedClients = CopyOnWriteArrayList<ScreenWebSocket>()

    // Shared files exposed to the TV Web Receiver
    val sharedFiles = CopyOnWriteArrayList<SharedFile>()

    // Persistent state tracking
    @Volatile var isMirroringActive: Boolean = false
    @Volatile var currentAspectMode: String = "cover" // "cover", "stretch", "fit"

    // Latest screen capture frame buffer
    @Volatile var latestFrameBytes: ByteArray? = null
    @Volatile var latestFrameTimestamp: Long = 0

    // Currently active cast media
    @Volatile var currentMediaUri: Uri? = null
    @Volatile var currentMediaMimeType: String? = null
    @Volatile var currentMediaTitle: String? = null

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return ScreenWebSocket(handshake)
    }

    override fun serve(session: IHTTPSession): Response {
        // Handle WebSocket handshake if requested
        if (isWebsocketRequested(session)) {
            return super.serve(session)
        }

        val uri = session.uri
        try {
            when {
                // 1. Root & Static TV Web Receiver Assets
                uri == "/" || uri == "/index.html" -> {
                    return serveAsset("web/index.html", "text/html")
                }
                uri == "/style.css" -> {
                    return serveAsset("web/style.css", "text/css")
                }
                uri == "/app.js" -> {
                    return serveAsset("web/app.js", "application/javascript")
                }

                // 2. Status & State Synchronization Endpoint
                uri == "/api/status" -> {
                    return serveStatusJson()
                }

                // 3. High-Performance Live Screen Stream (MJPEG)
                uri == "/stream.mjpg" -> {
                    return serveMjpegStream()
                }

                // 4. Single Frame Endpoint (Ultra-fast polling fallback)
                uri.startsWith("/api/frame") -> {
                    val frame = latestFrameBytes
                    if (frame != null) {
                        val response = newFixedLengthResponse(
                            Response.Status.OK,
                            "image/jpeg",
                            ByteArrayInputStream(frame),
                            frame.size.toLong()
                        )
                        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                        response.addHeader("Pragma", "no-cache")
                        response.addHeader("Access-Control-Allow-Origin", "*")
                        return response
                    }
                    return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
                }

                // 5. File Catalog API
                uri == "/api/files" -> {
                    return serveFilesJson()
                }

                // 6. File Download Endpoint
                uri.startsWith("/files/download/") -> {
                    val fileId = uri.removePrefix("/files/download/")
                    val file = sharedFiles.find { it.id == fileId }
                    if (file != null) {
                        return serveStreamableMedia(file.uri, file.mimeType, session, file.name, isAttachment = true)
                    }
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
                }

                // 7. Active Media Stream (Direct 16:9 Video/Photo Beaming)
                uri.startsWith("/media/active") -> {
                    val activeUri = currentMediaUri
                    val mime = currentMediaMimeType ?: "video/mp4"
                    if (activeUri != null) {
                        return serveStreamableMedia(activeUri, mime, session, currentMediaTitle, isAttachment = false)
                    }
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No active media")
                }

                else -> {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server Error: ${e.message}")
        }
    }

    private fun serveAsset(assetPath: String, mimeType: String): Response {
        val inputStream = context.assets.open(assetPath)
        val response = newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    /**
     * Serves current state so TV browser immediately syncs upon opening or reconnecting.
     */
    private fun serveStatusJson(): Response {
        val json = JSONObject().apply {
            put("isMirroring", isMirroringActive)
            put("aspectMode", currentAspectMode)
            put("hasMedia", currentMediaUri != null)
            if (currentMediaUri != null) {
                val mediaObj = JSONObject().apply {
                    put("url", "/media/active")
                    put("title", currentMediaTitle ?: "Media")
                    put("isVideo", currentMediaMimeType?.startsWith("video/") ?: true)
                    put("mimeType", currentMediaMimeType ?: "video/mp4")
                }
                put("activeMedia", mediaObj)
            }
        }
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    /**
     * Continuous hardware-accelerated MJPEG stream for modern TV browsers.
     */
    private fun serveMjpegStream(): Response {
        val boundary = "cast16x9frameboundary"
        val mjpegStream = object : InputStream() {
            private var currentBuffer: ByteArray? = null
            private var bufferPos = 0
            private var lastServedTime = 0L

            override fun read(): Int {
                val buf = ByteArray(1)
                val read = read(buf, 0, 1)
                return if (read > 0) buf[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                // If current frame buffer is exhausted, wait for next frame
                while (currentBuffer == null || bufferPos >= currentBuffer!!.size) {
                    if (!isMirroringActive) {
                        return -1 // Mirroring stopped, close stream
                    }

                    // Check for new frame
                    val frame = latestFrameBytes
                    val frameTime = latestFrameTimestamp
                    if (frame != null && frameTime > lastServedTime) {
                        lastServedTime = frameTime
                        val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                        val headerBytes = header.toByteArray(Charsets.US_ASCII)
                        val footerBytes = "\r\n".toByteArray(Charsets.US_ASCII)

                        val packet = ByteArray(headerBytes.size + frame.size + footerBytes.size)
                        System.arraycopy(headerBytes, 0, packet, 0, headerBytes.size)
                        System.arraycopy(frame, 0, packet, headerBytes.size, frame.size)
                        System.arraycopy(footerBytes, 0, packet, headerBytes.size + frame.size, footerBytes.size)

                        currentBuffer = packet
                        bufferPos = 0
                        break
                    }

                    // Sleep briefly to yield CPU
                    try {
                        Thread.sleep(15)
                    } catch (e: InterruptedException) {
                        return -1
                    }
                }

                val available = currentBuffer!!.size - bufferPos
                val toRead = if (len > available) available else len
                System.arraycopy(currentBuffer!!, bufferPos, b, off, toRead)
                bufferPos += toRead
                return toRead
            }
        }

        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            mjpegStream
        )
        response.addHeader("Cache-Control", "no-cache, private")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

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
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    /**
     * Serves media with reliable HTTP Range support (206 Partial Content) for smooth video seeking.
     */
    private fun serveStreamableMedia(
        contentUri: Uri,
        mimeType: String,
        session: IHTTPSession,
        filename: String?,
        isAttachment: Boolean = false
    ): Response {
        val cr = context.contentResolver
        var fileLength = -1L

        try {
            fileLength = cr.openAssetFileDescriptor(contentUri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            // Some providers don't give asset file descriptor, fallback to querying size
        }

        if (fileLength <= 0) {
            try {
                cr.query(contentUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx != -1) fileLength = cursor.getLong(sizeIdx)
                    }
                }
            } catch (e: Exception) {}
        }

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

            // Reliably skip to start position
            skipFully(stream, start)

            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                stream,
                contentLength
            )
            response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Access-Control-Allow-Origin", "*")
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
        response.addHeader("Access-Control-Allow-Origin", "*")
        if (filename != null) {
            response.addHeader("Content-Disposition", "$dispositionType; filename=\"$filename\"")
        }
        return response
    }

    private fun skipFully(inStream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0) {
            val skipped = inStream.skip(remaining)
            if (skipped <= 0) {
                if (inStream.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    /**
     * Updates latest captured frame in memory buffer and broadcasts to WebSocket clients.
     */
    fun onNewScreenFrame(frameData: ByteArray) {
        latestFrameBytes = frameData
        latestFrameTimestamp = System.currentTimeMillis()

        // Also broadcast over WebSocket (thread-safely with error resilience)
        for (client in connectedClients) {
            try {
                if (client.isOpen) {
                    synchronized(client) {
                        client.send(frameData)
                    }
                }
            } catch (e: Exception) {
                // Do not drop client on transient network hiccup
            }
        }
    }

    /**
     * Sends a thread-safe remote JSON control command to all connected TV Web Receivers.
     */
    fun sendControlCommand(commandJson: JSONObject) {
        val payload = commandJson.toString()
        for (client in connectedClients) {
            try {
                if (client.isOpen) {
                    synchronized(client) {
                        client.send(payload)
                    }
                }
            } catch (e: Exception) {
                // Ignore transient write error
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
            put("isVideo", isVideo)
        }
        sendControlCommand(json)
    }

    /**
     * Updates mirroring status and notifies TV browser.
     */
    fun setMirroringState(active: Boolean) {
        isMirroringActive = active
        val json = JSONObject().apply {
            put("action", "mirror_state")
            put("active", active)
        }
        sendControlCommand(json)
    }

    /**
     * Switches the 16:9 aspect ratio mode on connected TVs.
     * @param mode "cover" (16:9 zoom no bars), "stretch" (16:9 fill no bars), "fit" (contain)
     */
    fun setTvAspectMode(mode: String) {
        currentAspectMode = mode
        val json = JSONObject().apply {
            put("action", "set_aspect")
            put("mode", mode)
        }
        sendControlCommand(json)
    }

    inner class ScreenWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            connectedClients.add(this)
            // Immediately send current state upon connection
            try {
                val stateJson = JSONObject().apply {
                    put("action", "init_state")
                    put("isMirroring", isMirroringActive)
                    put("aspectMode", currentAspectMode)
                    put("hasMedia", currentMediaUri != null)
                    if (currentMediaUri != null) {
                        put("mediaUrl", "/media/active")
                        put("mediaTitle", currentMediaTitle ?: "Media")
                        put("isVideo", currentMediaMimeType?.startsWith("video/") ?: true)
                    }
                }
                synchronized(this) {
                    send(stateJson.toString())
                }
            } catch (e: Exception) {}
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            connectedClients.remove(this)
        }

        override fun onMessage(message: WebSocketFrame?) {
            // Heartbeat / ping from TV client
            if (message != null && message.textPayload == "ping") {
                try {
                    synchronized(this) {
                        send("pong")
                    }
                } catch (e: Exception) {}
            }
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: java.io.IOException?) {
            // Clean close on unrecoverable socket failure
            connectedClients.remove(this)
        }
    }

    fun getConnectedClientCount(): Int = connectedClients.size
}
