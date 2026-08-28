package com.chrispixel.chrisai.data.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

sealed class SttEvent {
    object Listening : SttEvent()
    object Processing : SttEvent()
    data class Partial(val text: String) : SttEvent()
    data class Result(val text: String) : SttEvent()
    data class Error(val message: String) : SttEvent()
}

/**
 * v0.6 voice input wrapper over the platform SpeechRecognizer. Uses the system
 * recognizer (no extra dependency keeps the APK light). Handles lifecycle so a
 * service restart never throws; all callbacks arrive on the main thread.
 */
class SttController(private val context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var callback: ((SttEvent) -> Unit)? = null
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callback?.invoke(SttEvent.Listening)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listening = false
            callback?.invoke(SttEvent.Processing)
        }

        override fun onError(error: Int) {
            listening = false
            callback?.invoke(SttEvent.Error(errorMessage(error)))
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) {
                callback?.invoke(SttEvent.Error("No se entendió lo que dijiste."))
            } else {
                callback?.invoke(SttEvent.Result(text))
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                callback?.invoke(SttEvent.Partial(text))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Starts listening. Returns false when the device has no recognizer. */
    fun start(callback: (SttEvent) -> Unit): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            this.callback?.invoke(SttEvent.Error("Este dispositivo no tiene reconocimiento de voz."))
            return false
        }
        this.callback = callback
        val recognizer = ensureRecognizer()
        if (recognizer == null) {
            callback(SttEvent.Error("No se pudo iniciar el reconocimiento de voz."))
            return false
        }
        if (listening) return false
        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        }
        try {
            recognizer.startListening(intent)
            return true
        } catch (_: Throwable) {
            listening = false
            callback(SttEvent.Error("No se pudo iniciar el reconocimiento de voz."))
            return false
        }
    }

    fun stop() {
        val recognizer = recognizer ?: return
        if (!listening) return
        try {
            recognizer.stopListening()
        } catch (_: Throwable) {
            // ignore
        }
        listening = false
    }

    fun cancel() {
        listening = false
        try {
            recognizer?.cancel()
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun shutdown() {
        cancel()
        mainHandler.post {
            try {
                recognizer?.destroy()
            } catch (_: Throwable) {
                // ignore
            }
            recognizer = null
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        var current = recognizer
        if (current == null) {
            // SpeechRecognizer must be created on a thread with a Looper.
            current = SpeechRecognizer.createSpeechRecognizer(appContext)
            current.setRecognitionListener(listener)
            recognizer = current
        }
        return current
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Error de audio: no se capturó la voz."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sin permiso de micrófono."
        SpeechRecognizer.ERROR_NETWORK -> "Error de red al reconocer la voz."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de espera de red agotado."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado; prueba otra vez."
        SpeechRecognizer.ERROR_NO_MATCH -> "No se entendió lo que dijiste."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se detectó voz."
        else -> "Error de reconocimiento de voz."
    }
}