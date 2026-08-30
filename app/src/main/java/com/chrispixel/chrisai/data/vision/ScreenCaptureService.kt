package com.chrispixel.chrisai.data.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import androidx.core.content.ContextCompat
import com.chrispixel.chrisai.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v0.9 screen understanding capture (MediaProjection).
 *
 * Starts ONLY from an explicit user consent (the system projection dialog). It
 * captures periodic stills (bounded interval, JPEG) into the app sandbox and
 * publishes them on [VisionFrameBus]; nothing leaves the device except the
 * frames the user's active video call sends.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.chrispixel.chrisai.SCREEN_CAPTURE_START"
        const val ACTION_STOP = "com.chrispixel.chrisai.SCREEN_CAPTURE_STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "chrisai_visual"
        private const val NOTIFICATION_ID = 2002

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private var manager: MediaProjectionManager? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val processExecutor = Executors.newSingleThreadExecutor()

    private val running = AtomicBoolean(false)
    private val captureRequested = AtomicBoolean(false)

    private var intervalMs = 5_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val thread = HandlerThread("chrisai-screen").apply { start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapturing()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (running.get()) return START_NOT_STICKY
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
                val resultData = intent?.let { if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else @Suppress("DEPRECATION") it.getParcelableExtra(EXTRA_RESULT_DATA) }
                if (resultCode == -1 || resultData == null) {
                    VisionFrameBus.problem("No se obtuvo el permiso de pantalla.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("Compartiendo pantalla"))
                beginCapture(resultCode, resultData)
                return START_STICKY
            }
        }
    }

    private fun beginCapture(resultCode: Int, resultData: Intent) {
        try {
            val projection = manager?.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                VisionFrameBus.problem("No se pudo iniciar la captura de pantalla.")
                stopSelf()
                return
            }
            this.projection = projection
            if (Build.VERSION.SDK_INT >= 34) {
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopCapturing()
                        stopSelf()
                    }
                }, captureHandler)
            }

            val metrics = resources.displayMetrics
            // Half resolution keeps frames small and quota-friendly.
            val width = (metrics.widthPixels / 2).coerceIn(320, 1280)
            val height = (metrics.heightPixels / 2).coerceIn(320, 1600)
            intervalMs = 5_000L

            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            newReader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, captureHandler)
            reader = newReader

            val display = projection.createVirtualDisplay(
                "chrisai-screen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                captureHandler
            )
            virtualDisplay = display
            running.set(true)
            VisionFrameBus.setScreenActive(true)
            captureHandler?.post(::scheduleFrameCapture)
        } catch (e: Exception) {
            VisionFrameBus.problem("No se pudo capturar la pantalla: ${e.message.orEmpty()}")
            stopCapturing()
            stopSelf()
        }
    }

    private fun scheduleFrameCapture() {
        if (!running.get()) return
        captureRequested.set(true)
        captureHandler?.postDelayed(::scheduleFrameCapture, intervalMs)
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        if (!captureRequested.compareAndSet(true, false)) {
            image.close()
            return
        }
        processExecutor.execute {
            try {
                saveFrame(image)
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }
    }

    private fun saveFrame(image: Image) {
        val dir = File(filesDir, "attachments/screen")
        if (!dir.exists()) dir.mkdirs()
        val bitmap = rgbaToBitmap(image, 960)
        val file = File(dir, "screen_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        VisionFrameBus.publish(VisionFrameBus.SOURCE_SCREEN, file.absolutePath)
    }

    private fun rgbaToBitmap(image: Image, maxDimension: Int): Bitmap {
        val planes = image.planes[0]
        val buffer = planes.buffer
        val pixelStride = planes.pixelStride
        val rowStride = planes.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        var bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest > maxDimension) {
            val scale = maxDimension.toFloat() / largest
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        }
        return bitmap
    }

    private fun stopCapturing() {
        if (!running.compareAndSet(true, false)) {
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
            VisionFrameBus.setScreenActive(false)
            return
        }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        VisionFrameBus.setScreenActive(false)
    }

    override fun onDestroy() {
        stopCapturing()
        processExecutor.shutdown()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ChrisAI videollamada",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Captura activa de pantalla durante la videollamada"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ChrisAI")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}