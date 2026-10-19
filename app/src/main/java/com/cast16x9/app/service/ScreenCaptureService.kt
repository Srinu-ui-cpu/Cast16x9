package com.cast16x9.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
                    startForeground(NOTIFICATION_ID, buildNotification("Screen Mirroring Active in Full 16:9"))
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

        // Scale resolution for smooth Wi-Fi transmission (capped at 1280x720 or similar for optimal frame rate)
        val captureWidth = if (width > 1280) 1280 else width
        val captureHeight = (height * (captureWidth.toFloat() / width)).toInt()

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Cast16x9Display",
            captureWidth,
            captureHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        isCapturing = true

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

                // Compress frame to JPEG
                val baos = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                val frameBytes = baos.toByteArray()

                // Broadcast to TV web receiver
                serverInstance?.broadcastScreenFrame(frameBytes)

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
