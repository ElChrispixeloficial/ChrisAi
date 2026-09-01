package com.chrispixel.chrisai.data.media

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * v1.1: small independent media player for audio files (NOT the TTS live voice).
 *
 * Plays any local audio file with pause/resume, seek and a live position tick.
 * The state is exposed reactively so the UI can drive a progress bar. Only one
 * file plays at a time; starting a new file stops the previous one.
 */
class MediaPlayerController {

    sealed class PlaybackState {
        object Idle : PlaybackState()
        data class Playing(val path: String, val durationMs: Int, val positionMs: Int) : PlaybackState()
        data class Paused(val path: String, val durationMs: Int, val positionMs: Int) : PlaybackState()
        object Error : PlaybackState()
    }

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var reportedDuration = 0
    private val ticker = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val current = _state.value
            if (current is PlaybackState.Playing) {
                try {
                    _state.value = current.copy(positionMs = p.currentPosition)
                } catch (_: Throwable) {
                    // ignore
                }
            }
            ticker.postDelayed(this, 250)
        }
    }

    /** Starts playing [file]; pauses any current playback. */
    fun play(file: File) {
        stopTicker()
        releasePlayer()
        reportedDuration = 0
        _state.value = PlaybackState.Idle
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            reportedDuration = try { mp.duration } catch (_: Throwable) { 0 }.coerceAtLeast(0)
            mp.setOnCompletionListener { finishPlayback() }
            mp.setOnErrorListener { _, _, _ ->
                _state.value = PlaybackState.Error
                stopTicker()
                true
            }
            mp.start()
            player = mp
            _state.value = PlaybackState.Playing(file.absolutePath, reportedDuration, 0)
            ticker.post(tick)
        } catch (_: Throwable) {
            releasePlayer()
            _state.value = PlaybackState.Error
        }
    }

    fun pause() {
        val current = _state.value
        if (current !is PlaybackState.Playing) return
        try {
            player?.pause()
            stopTicker()
            _state.value = PlaybackState.Paused(current.path, current.durationMs, player?.currentPosition ?: current.positionMs)
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun resume() {
        val current = _state.value
        if (current !is PlaybackState.Paused) return
        val path = current.path
        try {
            player?.let { mp ->
                if (!mp.isPlaying) {
                    mp.start()
                    _state.value = PlaybackState.Playing(path, current.durationMs, mp.currentPosition)
                    ticker.post(tick)
                }
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            player?.seekTo(positionMs.coerceAtLeast(0))
            val pos = positionMs.coerceAtLeast(0)
            _state.value = when (val cur = _state.value) {
                is PlaybackState.Playing -> cur.copy(positionMs = pos)
                is PlaybackState.Paused -> cur.copy(positionMs = pos)
                else -> cur
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun stop() {
        stopTicker()
        releasePlayer()
        _state.value = PlaybackState.Idle
    }

    private fun finishPlayback() {
        stopTicker()
        releasePlayer()
        _state.value = PlaybackState.Idle
    }

    private fun stopTicker() {
        ticker.removeCallbacks(tick)
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Throwable) {
            // ignore
        }
        player = null
    }
}
