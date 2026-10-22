package com.cast16x9.app.server

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.cast16x9.app.model.SharedFile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

class WebReceiverServer(
    private val context: Context,
    port: Int = 8080
) : NanoWSD(port) {

    // Connected TV Web Receiver WebSocket clients
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

    /**
     * Enhanced WebSocket detection compatible with all TV & mobile browser headers.
     */
    override fun isWebsocketRequested(session: IHTTPSession): Boolean {
        val uri = session.uri.trimEnd('/')
        if (uri == "/ws/screen" || uri == "/ws") {
            return true
        }

        val headers = session.headers
        val upgrade = headers.entries.firstOrNull { it.key.equals("upgrade", ignoreCase = true) }?.value ?: ""
        val connection = headers.entries.firstOrNull { it.key.equals("connection", ignoreCase = true) }?.value ?: ""

        return upgrade.contains("websocket", ignoreCase = true) || connection.contains("Upgrade", ignoreCase = true)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')

        // Handle WebSocket handshake
        if (uri == "/ws/screen" || uri == "/ws" || isWebsocketRequested(session)) {
            return super.serve(session)
        }

        try {
            when {
                // 1. Root & Static TV Web Receiver Assets
                uri == "" || uri == "/index.html" -> {
                    return serveAsset("web/index.html", "text/html")
                }
                uri == "/style.css" -> {
                    return serveAsset("web/style.css", "text/css")
                }
                uri == "/app.js" -> {
                    return serveAsset("web/app.js", "application/javascript")
                }

                // 2. Status & State Synchronization Endpoint
                uri.startsWith("/api/status") -> {
                    return serveStatusJson()
                }

                // 3. High-Performance Live Screen Stream (MJPEG)
                uri.startsWith("/stream.mjpg") -> {
                    return serveMjpegStream()
                }

                // 4. Single Frame Endpoint (Ultra-fast polling fallback)
                uri.startsWith("/api/frame") -> {
                    return serveLatestFrame()
                }

                // 5. File Catalog API
                uri.startsWith("/api/files") -> {
                    return serveFilesJson()
                }

                // 6. File Download Endpoint
                uri.startsWith("/files/download/") -> {
                    val fileId = uri.removePrefix("/files/download/")
                    val file = sharedFiles.find { it.id == fileId }
                    if (file != null) {
                        return serveStreamableMedia(file.uri, file.mimeType, session, file.name, isAttachment = true)
                    }
                    return withCors(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found"))
                }

                // 7. Active Media Stream (Direct 16:9 Video/Photo Beaming with full Forward/Seeking support)
                uri.startsWith("/media/active") -> {
                    val activeUri = currentMediaUri
                    val mime = currentMediaMimeType ?: "video/mp4"
                    if (activeUri != null) {
                        return serveStreamableMedia(activeUri, mime, session, currentMediaTitle, isAttachment = false)
                    }
                    return withCors(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No active media"))
                }

                else -> {
                    return withCors(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return withCors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server Error: ${e.message}"))
        }
    }

    private fun withCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        response.addHeader("Access-Control-Allow-Headers", "*")
        return response
    }

    private fun serveAsset(assetPath: String, mimeType: String): Response {
        val inputStream = context.assets.open(assetPath)
        val response = newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        response.addHeader("Cache-Control", "no-cache")
        return withCors(response)
    }

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
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        return withCors(response)
    }

    private fun serveLatestFrame(): Response {
        val frame = latestFrameBytes
        if (frame != null && frame.isNotEmpty()) {
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(frame),
                frame.size.toLong()
            )
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.addHeader("Pragma", "no-cache")
            return withCors(response)
        }
        // Fallback: Return 204 when no frame is available yet
        return withCors(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, ""))
    }

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
                while (currentBuffer == null || bufferPos >= currentBuffer!!.size) {
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

                    try {
                        Thread.sleep(20)
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
        return withCors(response)
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
        response.addHeader("Cache-Control", "no-cache")
        return withCors(response)
    }

    /**
     * Hardware-accelerated media streaming with O(1) byte seeking for video forwarding.
     */
    private fun serveStreamableMedia(
        contentUri: Uri,
        mimeType: String,
        session: IHTTPSession,
        filename: String?,
        isAttachment: Boolean = false
    ): Response {
        val cr = context.contentResolver
        var pfd: ParcelFileDescriptor? = null
        var fileLength = -1L

        try {
            pfd = cr.openFileDescriptor(contentUri, "r")
            if (pfd != null) {
                fileLength = pfd.statSize
            }
        } catch (e: Exception) {
            // Fallback to AssetFileDescriptor
            try {
                cr.openAssetFileDescriptor(contentUri, "r")?.use { fileLength = it.length }
            } catch (ex: Exception) {}
        }

        if (fileLength <= 0) {
            try {
                cr.query(contentUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx != -1) fileLength = cursor.getLong(idx)
                    }
                }
            } catch (e: Exception) {}
        }

        val dispositionType = if (isAttachment) "attachment" else "inline"

        // Find Range header case-insensitively
        val rangeHeader = session.headers.entries.firstOrNull {
            it.key.equals("range", ignoreCase = true)
        }?.value

        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileLength > 0 && pfd != null) {
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
            val fis = FileInputStream(pfd.fileDescriptor)

            // Hardware O(1) seek forward/backward in the file
            fis.channel.position(start)

            val boundedStream = object : InputStream() {
                var bytesLeft = contentLength

                override fun read(): Int {
                    if (bytesLeft <= 0) return -1
                    val b = fis.read()
                    if (b != -1) bytesLeft--
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (bytesLeft <= 0) return -1
                    val toRead = if (len > bytesLeft) bytesLeft.toInt() else len
                    val read = fis.read(b, off, toRead)
                    if (read > 0) bytesLeft -= read
                    return read
                }

                override fun close() {
                    try { fis.close() } catch (e: Exception) {}
                    try { pfd.close() } catch (e: Exception) {}
                }
            }

            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                boundedStream,
                contentLength
            )
            response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", contentLength.toString())
            if (filename != null) {
                response.addHeader("Content-Disposition", "$dispositionType; filename=\"$filename\"")
            }
            return withCors(response)
        }

        // Full content response (when starting from beginning or no range header)
        val fullStream = if (pfd != null) {
            FileInputStream(pfd.fileDescriptor)
        } else {
            cr.openInputStream(contentUri) ?: return withCors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open stream"))
        }

        val response = if (fileLength > 0) {
            newFixedLengthResponse(Response.Status.OK, mimeType, fullStream, fileLength)
        } else {
            newChunkedResponse(Response.Status.OK, mimeType, fullStream)
        }
        response.addHeader("Accept-Ranges", "bytes")
        if (fileLength > 0) {
            response.addHeader("Content-Length", fileLength.toString())
        }
        if (filename != null) {
            response.addHeader("Content-Disposition", "$dispositionType; filename=\"$filename\"")
        }
        return withCors(response)
    }

    fun onNewScreenFrame(frameData: ByteArray) {
        latestFrameBytes = frameData
        latestFrameTimestamp = System.currentTimeMillis()

        // Push frame to WebSocket clients if connected
        for (client in connectedClients) {
            try {
                if (client.isOpen) {
                    synchronized(client) {
                        client.send(frameData)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun sendControlCommand(commandJson: JSONObject) {
        val payload = commandJson.toString()
        for (client in connectedClients) {
            try {
                if (client.isOpen) {
                    synchronized(client) {
                        client.send(payload)
                    }
                }
            } catch (e: Exception) {}
        }
    }

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
            put("unmute", true)
        }
        sendControlCommand(json)
    }

    fun setMirroringState(active: Boolean) {
        isMirroringActive = active
        val json = JSONObject().apply {
            put("action", "mirror_state")
            put("active", active)
        }
        sendControlCommand(json)
    }

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
            connectedClients.remove(this)
        }
    }

    fun getConnectedClientCount(): Int = connectedClients.size
}
