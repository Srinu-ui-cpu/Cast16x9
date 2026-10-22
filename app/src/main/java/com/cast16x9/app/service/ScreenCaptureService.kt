package com.cast16x9.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cast16x9.app.MainActivity
import com.cast16x9.app.R
import com.cast16x9.app.server.WebReceiverServer
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isCapturing = false
    private var lastFrameTime = 0L
    private val frameIntervalMs = 33L // ~30 fps target

    companion object {
        const val ACTION_START = "com.cast16x9.action.START"
        const val ACTION_STOP = "com.cast16x9.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DENSITY = "extra_density"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cast16x9_mirror_channel"

        var serverInstance: WebReceiverServer? = null
        var isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
                val density = intent.getIntExtra(EXTRA_DENSITY, 320)

                if (resultData != null) {
                    val notification = buildNotification("Screen Mirroring Active in Full 16:9")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    startCapture(resultCode, resultData, width, height, density)
                    isServiceRunning = true
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isServiceRunning = false
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        width: Int,
        height: Int,
        density: Int
    ) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        // Android 14+ explicitly requires callback registration prior to creating VirtualDisplay
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, backgroundHandler)

        // High-Definition 1080p / 4K Capture Engine (Eliminates 360p/480p blur)
        var targetW = width
        var targetH = height
        var targetDensity = density

        if (width <= 600 && height <= 600) {
            // Smartwatch upscale: renders watch dials, text, and UI at crisp 1080x1080 at 400 DPI
            targetW = 1080
            targetH = 1080
            targetDensity = 400
        } else {
            // Full HD 1080p Engine: Preserves full 1080p resolution without downscaling to 480p
            val maxLimit = 1920
            if (width > maxLimit || height > maxLimit) {
                if (width >= height) {
                    targetW = maxLimit
                    targetH = (height * (maxLimit.toFloat() / width)).toInt()
                } else {
                    targetH = maxLimit
                    targetW = (width * (maxLimit.toFloat() / height)).toInt()
                }
            }
        }

        if (targetW % 2 != 0) targetW--
        if (targetH % 2 != 0) targetH--
        val captureWidth = targetW
        val captureHeight = targetH

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Cast16x9Display",
            captureWidth,
            captureHeight,
            targetDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        isCapturing = true
        serverInstance?.setMirroringState(true)

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < frameIntervalMs) {
                // Drop frame to preserve battery and maintain steady 30fps
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            var image: Image? = null
            try {
                image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * captureWidth

                val bitmap = Bitmap.createBitmap(
                    captureWidth + rowPadding / pixelStride,
                    captureHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop out stride padding if present
                val croppedBitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight)
                } else {
                    bitmap
                }

                // Compress frame at 90% High Quality (Razor-sharp 1080p/4K detail)
                val baos = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                val frameBytes = baos.toByteArray()

                // Broadcast to TV web receiver buffer and clients
                serverInstance?.onNewScreenFrame(frameBytes)

                if (croppedBitmap != bitmap) {
                    croppedBitmap.recycle()
                }
                bitmap.recycle()
            } catch (e: Exception) {
                // Ignore transient frame capture errors
            } finally {
                image?.close()
            }
        }, backgroundHandler)
    }

    private fun stopCapture() {
        isCapturing = false
        serverInstance?.setMirroringState(false)
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        stopCapture()
        handlerThread?.quitSafely()
        isServiceRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Mirroring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status while screen is being shared to TV"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cast16x9 - Screen Sharing")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
