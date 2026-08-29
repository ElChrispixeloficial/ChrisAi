package com.chrispixel.chrisai.data.live

/**
 * Deterministic state machine for a ChrisAI Live session (v0.8).
 *
 * Pure Kotlin (no Android, no timing, no side effects): the engine owns the
 * SpeechRecognizer/TTS and only feeds events. Guards are enforced here so the
 * session can never get stuck in an accidental listening loop: consecutive
 * failures and silences are bounded, and after a hard lockout only an explicit
 * [LiveEvent.Start] resumes the session.
 *
 * Happy loop:
 *   Start -> LISTENING -> TranscriptionReady -> THINKING -> GenerationStarted
 *   -> GENERATING -> GenerationFinished -> SPEAKING -> TtsFinished -> LISTENING
 *
 * Barge in while speaking:
 *   SPEAKING --(BargeIn)--> INTERRUPTED --(ListenAgain)--> LISTENING
 */
class LiveStateMachine(
    private val maxConsecutiveFailures: Int = 3,
    private val maxSilences: Int = 2,
    private val autoListenAfterSpeech: Boolean = true
) {

    var state: LiveState = LiveState()
        private set

    private var silenceStreak = 0
    private var lockoutReason: LiveErrorReason? = null

    /** Applies [event]; never throws. Returns the new state with the outcome. */
    fun on(event: LiveEvent): LiveTransition {
        val before = state
        val wasLocked = lockoutReason != null
        state = when (event) {
            is LiveEvent.Start -> onStart()
            LiveEvent.End -> onEnd()
            LiveEvent.SpeechDetected -> onSpeechDetected()
            LiveEvent.SpeechTimeout -> onSpeechTimeout()
            LiveEvent.TranscriptionReady -> onTranscriptionReady()
            is LiveEvent.Failure -> onFailure(event.reason)
            LiveEvent.GenerationStarted -> onGenerationStarted()
            LiveEvent.GenerationFinished -> onGenerationFinished()
            LiveEvent.TtsFinished -> onTtsFinished()
            LiveEvent.ToolStarted, LiveEvent.ToolFinished -> state // emitted at THINKING; no stage change
            LiveEvent.BargeIn -> onBargeIn()
            LiveEvent.Interrupt -> onInterrupt()
            LiveEvent.ListenAgain -> onListenAgain()
        }
        val outcome = outcomeOf(before, event, wasLocked)
        return LiveTransition(state, outcome)
    }

    /** Mute/unmute the mic (affects the rendered indicator, not the flow). */
    fun setMicMuted(muted: Boolean): LiveState {
        state = state.copy(micMuted = muted)
        return state
    }

    fun setVisionActive(active: Boolean): LiveState {
        state = state.copy(visionActive = active)
        return state
    }

    /** True when the session ended in ERROR with retries exhausted (user must Start). */
    val lockedOut: Boolean get() = lockoutReason != null

    private fun outcomeOf(before: LiveState, event: LiveEvent, wasLocked: Boolean): TransitionOutcome = when (event) {
        LiveEvent.ToolStarted, LiveEvent.ToolFinished ->
            TransitionOutcome.IGNORED
        is LiveEvent.Failure ->
            if (before.stage != LiveStage.IDLE) TransitionOutcome.ACCEPTED else TransitionOutcome.REJECTED
        LiveEvent.SpeechDetected ->
            if (before.stage == LiveStage.LISTENING) TransitionOutcome.ACCEPTED else TransitionOutcome.REJECTED
        LiveEvent.SpeechTimeout ->
            if (before.stage == LiveStage.LISTENING) TransitionOutcome.ACCEPTED else TransitionOutcome.REJECTED
        LiveEvent.TranscriptionReady ->
            if (before.stage == LiveStage.LISTENING) TransitionOutcome.ACCEPTED else TransitionOutcome.REJECTED
        LiveEvent.GenerationStarted ->
            if (before.stage == LiveStage.THINKING) TransitionOutcome.ACCEPTED else TransitionOutcome.REJECTED
        LiveEvent.GenerationFinished ->
            if (before.stage == LiveStage.GENERATING) TransitionOutcome.ACCEPTED else TransitionOutcome.REJECTED
        LiveEvent.TtsFinished ->
            if (before.stage == LiveStage.SPEAKING) TransitionOutcome.ACCEPTED else TransitionOutcome.REJECTED
        LiveEvent.BargeIn ->
            if (before.stage in setOf(LiveStage.SPEAKING, LiveStage.GENERATING)) TransitionOutcome.ACCEPTED
            else TransitionOutcome.REJECTED
        LiveEvent.Interrupt ->
            if (before.stage != LiveStage.IDLE) TransitionOutcome.ACCEPTED else TransitionOutcome.REJECTED
        LiveEvent.ListenAgain ->
            if (before.stage in setOf(LiveStage.INTERRUPTED, LiveStage.ERROR) && !wasLocked)
                TransitionOutcome.ACCEPTED
            else TransitionOutcome.REJECTED
        LiveEvent.End ->
            if (before.stage != LiveStage.IDLE) TransitionOutcome.ACCEPTED else TransitionOutcome.IGNORED
        LiveEvent.Start ->
            if (before.stage == LiveStage.IDLE || (before.stage == LiveStage.ERROR && wasLocked))
                TransitionOutcome.ACCEPTED
            else TransitionOutcome.REJECTED
    }

    private fun onStart(): LiveState {
        if (state.stage != LiveStage.IDLE && state.stage != LiveStage.ERROR) return state
        silenceStreak = 0
        lockoutReason = null
        return state.copy(stage = LiveStage.LISTENING, consecutiveFailures = 0, errorReason = null)
    }

    private fun onEnd(): LiveState = LiveState(
        micMuted = state.micMuted,
        visionActive = state.visionActive
    )

    private fun onSpeechDetected(): LiveState {
        if (state.stage != LiveStage.LISTENING) return state
        silenceStreak = 0
        return state // still listening (voice is coming in)
    }

    private fun onSpeechTimeout(): LiveState {
        if (state.stage != LiveStage.LISTENING) return state
        silenceStreak++
        if (silenceStreak >= maxSilences) return fail(LiveErrorReason.STT_TIMEOUT)
        return state // keep listening; a still device cannot loop forever
    }

    private fun onTranscriptionReady(): LiveState {
        if (state.stage != LiveStage.LISTENING) return state
        return state.copy(stage = LiveStage.THINKING)
    }

    /** The engine recorded the spoken text for the current turn (stage THINKING). */
    fun rememberUserText(text: String): LiveState {
        if (state.stage == LiveStage.THINKING) {
            state = state.copy(lastUserText = text)
        }
        return state
    }

    private fun onGenerationStarted(): LiveState {
        if (state.stage != LiveStage.THINKING) return state
        return state.copy(stage = LiveStage.GENERATING)
    }

    private fun onGenerationFinished(): LiveState {
        if (state.stage != LiveStage.GENERATING) return state
        return state.copy(stage = LiveStage.SPEAKING)
    }

    private fun onTtsFinished(): LiveState {
        if (state.stage != LiveStage.SPEAKING) return state
        return if (autoListenAfterSpeech) {
            state.copy(stage = LiveStage.LISTENING, turnCount = state.turnCount + 1)
        } else {
            state.copy(stage = LiveStage.IDLE)
        }
    }

    private fun onBargeIn(): LiveState {
        if (state.stage !in setOf(LiveStage.SPEAKING, LiveStage.GENERATING)) return state
        return state.copy(stage = LiveStage.INTERRUPTED)
    }

    private fun onInterrupt(): LiveState {
        if (state.stage == LiveStage.IDLE) return state
        return state.copy(stage = LiveStage.INTERRUPTED)
    }

    private fun onListenAgain(): LiveState {
        if (state.stage !in setOf(LiveStage.INTERRUPTED, LiveStage.ERROR)) return state
        if (lockoutReason != null) return state
        return state.copy(stage = LiveStage.LISTENING)
    }

    private fun onFailure(reason: LiveErrorReason): LiveState = fail(reason)

    private fun fail(reason: LiveErrorReason): LiveState {
        val failures = state.consecutiveFailures + 1
        if (failures >= maxConsecutiveFailures) {
            lockoutReason = LiveErrorReason.TOO_MANY_FAILURES
            return LiveState(
                stage = LiveStage.ERROR,
                micMuted = state.micMuted,
                visionActive = state.visionActive,
                consecutiveFailures = failures,
                errorReason = LiveErrorReason.TOO_MANY_FAILURES
            )
        }
        return state.copy(
            stage = LiveStage.ERROR,
            consecutiveFailures = failures,
            errorReason = reason
        )
    }
}