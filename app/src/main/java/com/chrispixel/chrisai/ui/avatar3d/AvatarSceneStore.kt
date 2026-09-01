package com.chrispixel.chrisai.ui.avatar3d

import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.live.LiveStage

/**
 * Thread-safe channel between the Compose/UI thread (inputs) and the GL thread
 * (renderer). All writes are volatile scalar stores; no GL calls outside the
 * render thread. Touch drag adjusts orbit; [autoOrbit] re-engages slowly.
 */
internal class AvatarSceneStore {
    @Volatile var emotion: Emotion = Emotion.NEUTRAL
    @Volatile var stage: LiveStage? = null
    @Volatile var intensity: Float = 0f
    @Volatile var autoOrbit: Boolean = true
    @Volatile var yawDeg: Float = 0f
    @Volatile var pitchDeg: Float = -10f

    fun drag(dx: Float, dy: Float) {
        autoOrbit = false
        yawDeg += dx * 0.35f
        pitchDeg = (pitchDeg + dy * 0.25f).coerceIn(-40f, 20f)
    }

    fun resetView() {
        autoOrbit = true
        yawDeg = 0f
        pitchDeg = -10f
    }
}