package com.chrispixel.chrisai.data.vision

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.1 controlled camera capture (Camera2).
 *
 * Captures ONE JPEG still every [intervalSec] seconds (bounded 2..60) instead of
 * streaming video frame-by-frame: low data, low quota, explicit start/stop.
 * Front/back lens switching works "in hot" (reopens the other camera without
 * tearing down the frame pipeline / call), so [VisionFrameBus] keeps flowing
 * across the switch. Permission problems and device failures surface as
 * [onProblem] and never crash.
 */
class CameraCaptureSession(context: Context) {

    companion object {
        private const val MAX_JPEG_BYTES = 1_200_000L
        const val FACE_BACK = CameraCharacteristics.LENS_FACING_BACK
        const val FACE_FRONT = CameraCharacteristics.LENS_FACING_FRONT
    }

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var captureExecutor = Executors.newSingleThreadExecutor()

    private val running = AtomicBoolean(false)
    private val captureRequested = AtomicBoolean(false)

    private var intervalMs = 5_000L
    private var outputDir: File? = null
    private var onFrame: (File) -> Unit = {}
    private var offProblem: (String) -> Unit = {}

    private var lensFacing = FACE_BACK

    val isActive: Boolean get() = running.get()
    val currentFacing: Int get() = lensFacing

    /** Preferred lens for the next start/switch (BACK or FRONT). */
    fun setLensFacing(facing: Int) {
        lensFacing = if (facing == FACE_FRONT) FACE_FRONT else FACE_BACK
    }

    /** Starts periodic captures. [onProblem] fires for permission/device errors. */
    fun start(intervalSec: Int, dir: File, onFrame: (File) -> Unit, onProblem: (String) -> Unit) {
        if (running.get()) return
        if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onProblem("Sin permiso de cámara. El vídeo seguirá con pantalla/audio.")
            return
        }
        intervalMs = intervalSec.coerceIn(2, 60) * 1000L
        outputDir = dir
        this.onFrame = onFrame
        offProblem = onProblem
        startCameraInternal()
    }

    /**
     * Switches the lens (front ↔ back) keeping the capture pipeline alive.
     * The frame channel ([VisionFrameBus]) is NOT marked inactive during the
     * switch, so the view/call state does not flicker.
     */
    fun switchCamera(facing: Int) {
        if (!running.get()) {
            setLensFacing(facing)
            return
        }
        lensFacing = if (facing == FACE_FRONT) FACE_FRONT else FACE_BACK
        releaseResources()
        startCameraInternal()
    }

    /** Stops captures immediately and releases the camera. */
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            // Ensure resources are released even if never started.
            releaseResources()
            return
        }
        release()
    }

    private fun startCameraInternal() {
        val problem: (String) -> Unit = offProblem
        try {
            startThread()
            VisionFrameBus.setCameraActive(true)
            val id = firstCameraId(lensFacing) ?: run {
                problem("No hay cámara disponible en este dispositivo.")
                stop()
                return
            }
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val sensorSize = pickOutputSize(characteristics)
            val newReader = ImageReader.newInstance(sensorSize.width, sensorSize.height, ImageFormat.JPEG, 2)
            newReader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, cameraHandler)
            reader = newReader
            if (!running.compareAndSet(false, true)) return
            cameraManager.openCamera(id, cameraStateCallback, cameraHandler)
        } catch (e: Exception) {
            problem("No se pudo iniciar la cámara: ${e.message.orEmpty()}")
            release()
        }
    }

    /** Full stop: releases everything and mirrors 'inactive' on the bus. */
    private fun release() {
        releaseResources()
        VisionFrameBus.setCameraActive(false)
    }

    /** Releases hardware handles but keeps the frame bus state untouched (switch path). */
    private fun releaseResources() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        captureRequested.set(false)
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        running.set(false)
    }

    private fun startThread() {
        val thread = HandlerThread("chrisai-camera").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private fun firstCameraId(facing: Int): String? {
        val ids = cameraManager.cameraIdList
        return ids.firstOrNull { id ->
            val traits = cameraManager.getCameraCharacteristics(id)
            traits.get(CameraCharacteristics.LENS_FACING) == facing
        } ?: ids.firstOrNull()
    }

    private fun pickOutputSize(characteristics: CameraCharacteristics): Size {
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            .orEmpty()
        val target = 1280
        return sizes
            .filter { it.width <= 1920 && it.width * it.height > 0 }
            .minByOrNull { kotlin.math.abs(it.width - target) + kotlin.math.abs(it.height - target / 16 * 9) }
            ?: Size(target, 720)
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            camera = device
            startCaptureSession(device)
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            running.set(false)
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            running.set(false)
            VisionFrameBus.problem("La cámara se detuvo (error $error).")
        }
    }

    private fun startCaptureSession(device: CameraDevice) {
        val currentReader = reader ?: return
        try {
            device.createCaptureSession(
                listOf(currentReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        this@CameraCaptureSession.session = session
                        startRepeating(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        VisionFrameBus.problem("No se pudo configurar la cámara.")
                        stop()
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            VisionFrameBus.problem("No se pudo iniciar la captura: ${e.message.orEmpty()}")
            stop()
        }
    }

    private fun startRepeating(session: CameraCaptureSession) {
        val request = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(reader!!.surface)
            set(
                CaptureRequest.JPEG_ORIENTATION,
                // Front sensors are mirrored: rotate 180° so the preview reads natural.
                if (lensFacing == FACE_FRONT) 270 else 90
            )
        }.build()
        try {
            session.setRepeatingRequest(request, null, cameraHandler)
        } catch (_: Exception) {
            return
        }
        // Kick the periodic cadence from the camera thread.
        cameraHandler?.post(::scheduleFrameCapture)
    }

    private fun scheduleFrameCapture() {
        if (!running.get()) return
        captureRequested.set(true)
        cameraHandler?.postDelayed(::scheduleFrameCapture, intervalMs)
    }

    private fun onImageAvailable(reader: ImageReader) {
        if (!captureRequested.compareAndSet(true, false)) {
            // Not our capture turn: release immediately to keep the stream flowing.
            reader.acquireLatestImage()?.close()
            return
        }
        val image = reader.acquireLatestImage() ?: return
        try {
            saveJpeg(image)
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun saveJpeg(image: android.media.Image) {
        val dir = outputDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (bytes.size > MAX_JPEG_BYTES) return
        val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        onFrame(file)
    }
}