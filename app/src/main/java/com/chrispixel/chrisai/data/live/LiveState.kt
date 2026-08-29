package com.chrispixel.chrisai.data.live

/** Stages of a ChrisAI Live session (v0.8). See [LiveStateMachine]. */
enum class LiveStage {
    IDLE,
    LISTENING,
    THINKING,
    GENERATING,
    SPEAKING,
    INTERRUPTED,
    ERROR
}

/** Reasons a session can land on [LiveStage.ERROR] or be rejected. */
enum class LiveErrorReason(val message: String) {
    NO_RECOGNIZER("Este dispositivo no tiene reconocimiento de voz."),
    NO_MIC_PERMISSION("Sin permiso de micrófono."),
    STT_TIMEOUT("No se detectó voz."),
    STT_NO_MATCH("No se entendió lo que dijiste."),
    STT_FAILED("El reconocimiento de voz falló."),
    API_FAILED("La API falló durante la sesión."),
    TTS_FAILED("El audio no pudo reproducirse."),
    VISION_FAILED("No se pudo capturar el contexto visual."),
    TOO_MANY_FAILURES("Demasiados fallos seguidos; la sesión se detuvo.")
}

/**
 * Immutable snapshot of a Live session. The [LiveStateMachine] is the only
 * producer; the UI renders this value.
 */
data class LiveState(
    val stage: LiveStage = LiveStage.IDLE,
    val micMuted: Boolean = false,
    val visionActive: Boolean = false,
    val turnCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val errorReason: LiveErrorReason? = null,
    val lastUserText: String? = null
) {
    val isActive: Boolean get() = stage != LiveStage.IDLE

    /** Visible indicator: mic is enabled and the session is listening. */
    val listening: Boolean get() = stage == LiveStage.LISTENING && !micMuted

    /** Visible indicator: ChrisAI is talking (or being interrupted). */
    val speaking: Boolean get() = stage == LiveStage.SPEAKING || stage == LiveStage.INTERRUPTED
}

/** Events consumed by [LiveStateMachine]. */
sealed class LiveEvent {
    /** User pressed "call": start a session (only valid from IDLE). */
    object Start : LiveEvent()

    /** User pressed "end": stop the session (valid in any active stage). */
    object End : LiveEvent()

    /** Recognizer started and picked the first speech frame ("hello"). */
    object SpeechDetected : LiveEvent()

    /** Recognizer fired ERROR_NO_MATCH / timeout without clear speech. */
    object SpeechTimeout : LiveEvent()

    /** STT produced a final transcription for the current turn. */
    object TranscriptionReady : LiveEvent()

    /** A turn failed for a concrete reason. */
    data class Failure(val reason: LiveErrorReason) : LiveEvent()

    /** Model call started (after intent analysis). */
    object GenerationStarted : LiveEvent()

    /** Stream finished; text is ready to speak. */
    object GenerationFinished : LiveEvent()

    /** TTS finished speaking the reply. */
    object TtsFinished : LiveEvent()

    /** A tool is being executed (ChrisTools). */
    object ToolStarted : LiveEvent()

    /** Tool finished. */
    object ToolFinished : LiveEvent()

    /** The user started speaking while ChrisAI was speaking (barge in). */
    object BargeIn : LiveEvent()

    /** Explicit user/tap interruption from any active stage. */
    object Interrupt : LiveEvent()

    /** Resume listening after an interruption/error. */
    object ListenAgain : LiveEvent()
}

enum class TransitionOutcome { ACCEPTED, IGNORED, REJECTED }

data class LiveTransition(
    val state: LiveState,
    val outcome: TransitionOutcome = TransitionOutcome.ACCEPTED
)