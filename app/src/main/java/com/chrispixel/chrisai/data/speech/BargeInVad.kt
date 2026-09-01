package com.chrispixel.chrisai.data.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * v1.1: real barge-in detection while ChrisAI is speaking in call mode.
 *
 * A lightweight energy-based voice activity detector (no extra dependency) that
 * records from the microphone while TTS plays and triggers the callback when the
 * user starts speaking over the assistant.
 *
 * How it avoids hearing its own voice:
 * - The noise floor is an exponential moving average of the signal energy, so the
 *   TTS audio coming through the speaker (and whatever plays around it) becomes
 *   the calibrated baseline.
 * - A trigger only fires when the signal stays clearly above that floor for a
 *   sustained amount of time ([MIN_VOICED_FRAMES] consecutive frames), which
 *   filters out isolated clicks and the tail of each TTS syllable.
 *
 * Lifecycle: call [start] once per utterance while the TTS is speaking and [stop]
 * when the utterance finishes. [start] resets calibration automatically.
 */
class BargeInVad(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null

    /** Fires (on the main thread) once per [start] when the user barges in. */
    fun start(onBargeIn: () -> Unit): Boolean {
        if (running) return true
        val granted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false

        val sampleRate = 16000
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) return false

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBytes * 2
            )
        } catch (_: Throwable) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Throwable) {}
            return false
        }

        running = true
        recorder = rec
        worker = Thread {
            runLoop(rec, sampleRate, onBargeIn)
        }.also { it.isDaemon = true }
        worker?.start()
        return true
    }

    private fun runLoop(rec: AudioRecord, sampleRate: Int, onBargeIn: () -> Unit) {
        // 20 ms frames -> fast response but still rejects clicks.
        val frameSamples = sampleRate / 50
        val buffer = ShortArray(frameSamples)
        var floor = 1e-4f // avoid log(0)
        var voicedFrames = 0
        var fired = false
        var coolDownFrames = 0
        val coolDownSamples = sampleRate / 50 * 75 // ~1.5 s after firing

        try {
            rec.startRecording()
            while (running) {
                val read = rec.read(buffer, 0, frameSamples)
                if (read <= 0) {
                    if (read < 0) {
                        // transient stream error: brief pause, don't kill the detector
                        try { Thread.sleep(30) } catch (_: InterruptedException) { break }
                    }
                    continue
                }

                var sum = 0.0
                for (i in 0 until read) {
                    val v = buffer[i].toInt()
                    sum += (v * v).toDouble()
                }
                val energy = (sum / read) / (32768.0 * 32768.0)
                val rms = sqrt(energy).toFloat()

                if (coolDownFrames > 0) {
                    coolDownFrames--
                    continue
                }

                // Adaptive floor: follows the TTS (and ambient) level so the
                // speaker output becomes the baseline, not the trigger itself.
                val triggerThreshold = floor * TRIGGER_GAIN
                if (rms > triggerThreshold) {
                    voicedFrames++
                    if (voicedFrames >= MIN_VOICED_FRAMES && !fired) {
                        fired = true
                        mainHandler.post(onBargeIn)
                        coolDownFrames = coolDownSamples
                    }
                } else {
                    if (voicedFrames > 0) voicedFrames--
                    // only track the floor when clearly below the trigger line
                    floor += (rms - floor) * FLOOR_ALPHA
                }
            }
        } catch (_: Throwable) {
            // recorder died: silently stop
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        recorder?.let {
            try { it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        recorder = null
        worker = null
    }

    val isRunning: Boolean get() = running

    private companion object {
        /** Multiply the adaptive floor by this to get the trigger line. */
        const val TRIGGER_GAIN = 2.2f
        /** Consecutive voiced frames required (~20 ms each) = 160 ms sustained. */
        const val MIN_VOICED_FRAMES = 8
        /** How fast the floor adapts to the ambient/TTS level. */
        const val FLOOR_ALPHA = 0.08f
    }
}