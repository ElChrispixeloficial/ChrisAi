package com.chrispixel.chrisai.data.vision

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * v0.9 in-process channel for periodic visual captures.
 *
 * Frames are small, bounded, already-saved JPEG files. Producers are the camera
 * (Camera2) and the screen (MediaProjection); the ViewModel consumes them only
 * while a video call with that source is active, so nothing is sent otherwise.
 */
object VisionFrameBus {

    const val SOURCE_CAMERA = "camera"
    const val SOURCE_SCREEN = "screen"

    data class VisionFrame(val source: String, val path: String)

    data class VisionProblem(val message: String)

    private val _frames = MutableSharedFlow<VisionFrame>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: SharedFlow<VisionFrame> = _frames.asSharedFlow()

    private val _problems = MutableSharedFlow<VisionProblem>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val problems: SharedFlow<VisionProblem> = _problems.asSharedFlow()

    // Source activity flags, written by the producers (camera session and the
    // projection service) so the ViewModel can mirror real device state.
    private val _cameraActive = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 1, replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val cameraActive: SharedFlow<Boolean> = _cameraActive.asSharedFlow()

    private val _screenActive = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 1, replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val screenActive: SharedFlow<Boolean> = _screenActive.asSharedFlow()

    fun publish(source: String, path: String) {
        _frames.tryEmit(VisionFrame(source, path))
    }

    fun problem(message: String) {
        _problems.tryEmit(VisionProblem(message))
    }

    fun setCameraActive(active: Boolean) {
        _cameraActive.tryEmit(active)
    }

    fun setScreenActive(active: Boolean) {
        _screenActive.tryEmit(active)
    }
}