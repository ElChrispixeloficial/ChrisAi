package com.chrispixel.chrisai.data.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class TtsStatus {
    object Unavailable : TtsStatus()
    object Idle : TtsStatus()
    data class Speaking(val messageId: String, val text: String) : TtsStatus()
    data class Paused(val messageId: String, val remainingText: String) : TtsStatus()
    data class Error(val messageId: String, val message: String) : TtsStatus()
}

/**
 * v0.6 voice output wrapper over the platform TextToSpeech engine.
 * Supports speak / pause / resume / stop per assistant message, is created on a
 * looper thread (engine requirement) and never crashes the app on init failure.
 */
class TtsController(context: Context) {

    private val appContext = context.applicationContext

    private val _status = MutableStateFlow<TtsStatus>(TtsStatus.Unavailable)
    val status: StateFlow<TtsStatus> = _status.asStateFlow()

    private var tts: TextToSpeech? = null
    private var currentMessageId: String? = null
    private var isReady = false
    private var pendingRate = 1.0f
    private var pendingVoiceName: String? = null

    sealed class InitResult {
        object Ok : InitResult()
        object Failed : InitResult()
    }

    private var onInit: ((InitResult) -> Unit)? = null

    /** Creates the engine; [callback] fires once init finishes (any thread). */
    fun initialize(callback: (InitResult) -> Unit) {
        onInit = callback
        // TextToSpeech requires a Looper-backed thread; post to main.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                // The init listener may not be able to reference the engine during
                // construction, so create it via a nullable holder assigned first.
                var holder: TextToSpeech? = null
                holder = TextToSpeech(appContext) { statusCode ->
                    val engine = holder
                    if (engine == null) {
                        isReady = false
                        tts = null
                        _status.value = TtsStatus.Unavailable
                        onInit?.invoke(InitResult.Failed)
                        onInit = null
                        return@TextToSpeech
                    }
                    isReady = statusCode == TextToSpeech.SUCCESS
                    if (isReady) {
                        engine.language = Locale("es", "ES")
                        engine.setSpeechRate(pendingRate)
                        pendingVoiceName?.let { applyVoice(engine, it) }
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                // no-op: state is set before speaking
                            }

                            override fun onDone(utteranceId: String?) {
                                if (utteranceId == currentMessageId) {
                                    _status.value = TtsStatus.Idle
                                    currentMessageId = null
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                _status.value = TtsStatus.Idle
                                currentMessageId = null
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                _status.value = TtsStatus.Idle
                                currentMessageId = null
                            }
                        })
                    }
                    tts = engine
                    onInit?.invoke(if (isReady) InitResult.Ok else InitResult.Failed)
                    onInit = null
                }
            } catch (_: Throwable) {
                isReady = false
                _status.value = TtsStatus.Unavailable
                onInit?.invoke(InitResult.Failed)
                onInit = null
            }
        }
    }

    fun available(): Boolean = isReady && tts != null

    fun speak(messageId: String, text: String, rate: Float) {
        val engine = tts ?: run { _status.value = TtsStatus.Unavailable; return }
        if (text.isBlank() || !isReady) return
        try {
            pendingRate = rate.coerceIn(0.5f, 2.0f)
            engine.setSpeechRate(pendingRate)
            currentMessageId = messageId
            _status.value = TtsStatus.Speaking(messageId, text)
            engine.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                Bundle(),
                messageId
            )
        } catch (_: Throwable) {
            _status.value = TtsStatus.Error(messageId, "No se pudo reproducir el texto.")
        }
    }

    /** Pauses the current utterance; resumes from the same point if supported. */
    fun pause() {
        val current = _status.value
        if (current !is TtsStatus.Speaking) return
        try {
            tts?.stop()
            // We cannot resume mid-utterance reliably, so keep the remaining text
            // approximatively (the engine consumed it all to be safe).
            _status.value = TtsStatus.Paused(current.messageId, current.text)
        } catch (_: Throwable) {
            _status.value = current
        }
    }

    fun resume(rate: Float) {
        val current = _status.value
        if (current !is TtsStatus.Paused) return
        speak(current.messageId, current.remainingText, rate)
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Throwable) {
            // ignored
        }
        currentMessageId = null
        _status.value = TtsStatus.Idle
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
            // ignored
        }
        tts = null
        isReady = false
    }

    /** Applies a cached voice preference once available. */
    fun setVoicePreference(name: String) {
        pendingVoiceName = name
        if (isReady) applyVoice(tts, name)
    }

    private fun applyVoice(engine: TextToSpeech?, name: String) {
        if (engine == null || name.isBlank()) return
        try {
            val voice = engine.voices?.firstOrNull { it.name == name || it.name == "$name." }
                ?: engine.voices?.firstOrNull { it.name.contains(name, ignoreCase = true) }
            if (voice != null) engine.voice = voice
        } catch (_: Throwable) {
            // ignore: keep default voice
        }
    }

    fun listVoices(): List<String> {
        if (!isReady) return emptyList()
        return try {
            tts?.voices?.map { it.name }?.sorted()?.distinct().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }
}