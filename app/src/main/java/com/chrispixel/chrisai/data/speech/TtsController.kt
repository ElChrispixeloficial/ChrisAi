package com.chrispixel.chrisai.data.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.chrispixel.chrisai.data.emotion.Emotion

sealed class TtsStatus {
    object Unavailable : TtsStatus()
    object Idle : TtsStatus()
    data class Speaking(val messageId: String, val text: String) : TtsStatus()
    data class Paused(val messageId: String, val remainingText: String) : TtsStatus()
    data class Error(val messageId: String, val message: String) : TtsStatus()
}

enum class VoiceGender { MALE, FEMALE, UNKNOWN }

/** v0.7 enriched voice info for the settings selector (gender is heuristic). */
data class TtsVoiceInfo(
    val name: String,
    val locale: Locale,
    val gender: VoiceGender,
    val isNetwork: Boolean,
    val isDefault: Boolean = false
) {
    val displayName: String
        get() = when (gender) {
            VoiceGender.MALE -> "👨 $name"
            VoiceGender.FEMALE -> "👩 $name"
            VoiceGender.UNKNOWN -> "🔊 $name"
        }

    val localeLabel: String
        get() = locale.displayName
}

/**
 * v0.7 voice output wrapper over the platform TextToSpeech engine.
 *
 * New in this version:
 * - text preprocessing via [TtsText] (removes emojis/Markdown before speech);
 * - configurable pitch in addition to rate;
 * - voice listing with gender/locale heuristics;
 * - voice preview;
 * - emotion-aware speech (gentle pitch/rate shadings when the engine allows).
 */
class TtsController(context: Context) {

    private val appContext = context.applicationContext
    private val previewLabel = "__voice_preview__"

    private val _status = MutableStateFlow<TtsStatus>(TtsStatus.Unavailable)
    val status: StateFlow<TtsStatus> = _status.asStateFlow()

    private var tts: TextToSpeech? = null
    private var currentMessageId: String? = null
    private var isReady = false
    private var pendingRate = 1.0f
    private var pendingPitch = 1.0f
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
                        engine.setPitch(pendingPitch)
                        pendingVoiceName?.let { applyVoice(engine, it) }
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                // no-op: state is set before speaking
                            }

                            override fun onDone(utteranceId: String?) {
                                if (utteranceId == currentMessageId || utteranceId == previewLabel) {
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

    /** Speaks [rawText] after TTS preprocessing (emojis/Markdown removed). */
    fun speak(messageId: String, rawText: String, rate: Float, pitch: Float = 1.0f) {
        val clean = TtsText.prepare(rawText)
        speakClean(messageId, clean, rate, pitch)
    }

    /**
     * Emotion-aware speech: applies gentle pitch/rate shading per emotion.
     * The engine always stays natural; neutral uses the user settings as-is.
     */
    fun speakWithEmotion(
        messageId: String,
        rawText: String,
        rate: Float,
        pitch: Float,
        emotion: Emotion
    ) {
        val clean = TtsText.prepare(rawText)
        if (clean.isBlank()) return
        val (emotionRate, emotionPitch) = emotionalParams(emotion, rate, pitch)
        speakClean(messageId, clean, emotionRate, emotionPitch)
    }

    private fun emotionalParams(emotion: Emotion, rate: Float, pitch: Float): Pair<Float, Float> {
        if (emotion == Emotion.GENERATING || emotion == Emotion.NEUTRAL) return rate to pitch
        return when (emotion) {
            Emotion.HAPPY -> (rate * 1.04f).coerceAtMost(1.35f) to (pitch * 1.06f).coerceAtMost(1.5f)
            Emotion.EXCITED -> (rate * 1.06f).coerceAtMost(1.4f) to (pitch * 1.04f).coerceAtMost(1.45f)
            Emotion.SAD -> (rate * 0.92f).coerceAtLeast(0.6f) to pitch
            Emotion.WORRIED -> (rate * 0.95f).coerceAtLeast(0.6f) to pitch
            Emotion.THOUGHTFUL -> (rate * 0.94f).coerceAtLeast(0.6f) to pitch
            else -> rate to pitch
        }
    }

    private fun speakClean(messageId: String, clean: String, rate: Float, pitch: Float) {
        val engine = tts ?: run { _status.value = TtsStatus.Unavailable; return }
        if (clean.isBlank() || !isReady) return
        try {
            pendingRate = rate.coerceIn(0.5f, 2.0f)
            pendingPitch = pitch.coerceIn(0.5f, 2.0f)
            engine.setSpeechRate(pendingRate)
            engine.setPitch(pendingPitch)
            currentMessageId = messageId
            _status.value = TtsStatus.Speaking(messageId, clean)
            engine.speak(
                clean,
                TextToSpeech.QUEUE_FLUSH,
                Bundle(),
                messageId
            )
        } catch (_: Throwable) {
            _status.value = TtsStatus.Error(messageId, "No se pudo reproducir el texto.")
        }
    }

    /** Speaks a sample line so the user can compare voices. */
    fun preview(text: String, rate: Float, pitch: Float) {
        speakClean(previewLabel, TtsText.prepare(text), rate, pitch)
    }

    /** Pauses the current utterance; resumes from the same point if supported. */
    fun pause() {
        val current = _status.value
        if (current !is TtsStatus.Speaking) return
        try {
            tts?.stop()
            _status.value = TtsStatus.Paused(current.messageId, current.text)
        } catch (_: Throwable) {
            _status.value = current
        }
    }

    fun resume(rate: Float, pitch: Float = 1.0f) {
        val current = _status.value
        if (current !is TtsStatus.Paused) return
        speakClean(current.messageId, current.remainingText, rate, pitch)
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
        if (isReady && name.isNotBlank()) applyVoice(tts, name)
    }

    private fun applyVoice(engine: TextToSpeech?, name: String) {
        if (engine == null || name.isBlank()) return
        try {
            val voice = engine.voices?.firstOrNull { it.name == name }
                ?: engine.voices?.firstOrNull { it.name.contains(name, ignoreCase = true) }
            if (voice != null) engine.voice = voice
        } catch (_: Throwable) {
            // ignore: keep default voice
        }
    }

    /** Enriched list of voices with gender/locale heuristics. */
    fun listVoices(): List<TtsVoiceInfo> {
        if (!isReady) return emptyList()
        return try {
            tts?.voices?.mapNotNull { voice ->
                TtsVoiceInfo(
                    name = voice.name,
                    locale = voice.locale,
                    gender = guessGender(voice),
                    isNetwork = voice.isNetworkConnectionRequired
                )
            }?.sortedWith(compareBy({ it.locale.language }, { it.gender != VoiceGender.FEMALE }, { it.name }))
                .orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Guesswork: Android doesn't publicly expose voice gender, so we infer
     *  from common naming patterns used by engines (Google, Samsung...). */
    private fun guessGender(voice: Voice): VoiceGender {
        val n = voice.name.lowercase(Locale.ROOT)
        val female = listOf(
            "female", "femenin", "mujer", "woman", "maria", "laura", "ana", "sofia",
            "elia", "paula", "carmen", "luisa", "carlota", "eugenia", "helena", "rosa",
            "vicky", "clar", "sami", "arlet", "irmi", "vena", "zola", "nola", "georgia"
        )
        val male = listOf(
            "male", "masculin", "hombre", "david", "jorge", "carlos", "pablo", "luis",
            "antonio", "raul", "pedro", "diego", "miguel", "daniel", "victor", "alex",
            "kevin", "thomas", "adrian", "jose", "lucien", "tomy"
        )
        return when {
            female.any { n.contains(it) } -> VoiceGender.FEMALE
            male.any { n.contains(it) } -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }
    }
}