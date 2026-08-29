package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.live.LiveErrorReason
import com.chrispixel.chrisai.data.live.LiveEvent
import com.chrispixel.chrisai.data.live.LiveStage
import com.chrispixel.chrisai.data.live.LiveStateMachine
import com.chrispixel.chrisai.data.live.TransitionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStateMachineTest {

    @Test
    fun `start only from idle`() {
        val m = LiveStateMachine()
        assertEquals(LiveStage.IDLE, m.state.stage)
        m.on(LiveEvent.Start)
        assertEquals(LiveStage.LISTENING, m.state.stage)
        // Starting again while active is rejected.
        val again = m.on(LiveEvent.Start)
        assertEquals(TransitionOutcome.REJECTED, again.outcome)
    }

    @Test
    fun `happy loop cycles and counts turns`() {
        val m = LiveStateMachine()
        m.on(LiveEvent.Start)                      // IDLE -> LISTENING
        m.on(LiveEvent.SpeechDetected)             // still LISTENING
        assertEquals(LiveStage.LISTENING, m.state.stage)
        m.on(LiveEvent.TranscriptionReady)         // LISTENING -> THINKING
        m.on(LiveEvent.GenerationStarted)          // THINKING -> GENERATING
        m.on(LiveEvent.GenerationFinished)         // GENERATING -> SPEAKING
        m.on(LiveEvent.TtsFinished)                // SPEAKING -> LISTENING
        assertEquals(LiveStage.LISTENING, m.state.stage)
        assertEquals(1, m.state.turnCount)

        m.on(LiveEvent.TranscriptionReady)
        m.on(LiveEvent.GenerationStarted)
        m.on(LiveEvent.GenerationFinished)
        m.on(LiveEvent.TtsFinished)
        assertEquals(2, m.state.turnCount)
        assertTrue(m.state.isActive)
    }

    @Test
    fun `barge in during speaking goes to interrupted the resumes listening`() {
        val m = LiveStateMachine()
        m.on(LiveEvent.Start)                      // LISTENING
        m.on(LiveEvent.TranscriptionReady)         // THINKING
        m.on(LiveEvent.GenerationStarted)          // GENERATING
        m.on(LiveEvent.GenerationFinished)         // SPEAKING
        assertEquals(LiveStage.SPEAKING, m.state.stage)

        val barge = m.on(LiveEvent.BargeIn)
        assertEquals(TransitionOutcome.ACCEPTED, barge.outcome)
        assertEquals(LiveStage.INTERRUPTED, m.state.stage)

        assertEquals(TransitionOutcome.ACCEPTED, m.on(LiveEvent.ListenAgain).outcome)
        assertEquals(LiveStage.LISTENING, m.state.stage)
    }

    @Test
    fun `barge in is rejected from other stages`() {
        val m = LiveStateMachine()
        m.on(LiveEvent.Start)
        assertEquals(TransitionOutcome.REJECTED, m.on(LiveEvent.BargeIn).outcome)
        m.on(LiveEvent.TranscriptionReady)
        assertEquals(TransitionOutcome.REJECTED, m.on(LiveEvent.BargeIn).outcome)
    }

    @Test
    fun `transcription before listening is rejected`() {
        val m = LiveStateMachine()
        assertEquals(TransitionOutcome.REJECTED, m.on(LiveEvent.TranscriptionReady).outcome)
    }

    @Test
    fun `silences are bounded and escalate to error`() {
        val m = LiveStateMachine(maxSilences = 2)
        m.on(LiveEvent.Start)
        assertEquals(TransitionOutcome.ACCEPTED, m.on(LiveEvent.SpeechTimeout).outcome)
        assertEquals(LiveStage.LISTENING, m.state.stage) // counts, keeps listening
        m.on(LiveEvent.SpeechTimeout)
        assertEquals(LiveStage.ERROR, m.state.stage)
        assertEquals(LiveErrorReason.STT_TIMEOUT, m.state.errorReason)
    }

    @Test
    fun `too many failures lock the session until an explicit start`() {
        val m = LiveStateMachine(maxConsecutiveFailures = 3)
        m.on(LiveEvent.Start)
        m.on(LiveEvent.TranscriptionReady)
        m.on(LiveEvent.Failure(LiveErrorReason.API_FAILED))
        assertEquals(LiveStage.ERROR, m.state.stage)
        m.on(LiveEvent.ListenAgain)
        assertEquals(LiveStage.LISTENING, m.state.stage)
        m.on(LiveEvent.TranscriptionReady)
        m.on(LiveEvent.Failure(LiveErrorReason.API_FAILED))
        m.on(LiveEvent.ListenAgain)
        m.on(LiveEvent.TranscriptionReady)
        m.on(LiveEvent.Failure(LiveErrorReason.API_FAILED))
        // Retries exhausted: locked OUT, ListenAgain is rejected.
        assertEquals(LiveStage.ERROR, m.state.stage)
        assertEquals(LiveErrorReason.TOO_MANY_FAILURES, m.state.errorReason)
        assertTrue(m.lockedOut)
        assertEquals(TransitionOutcome.REJECTED, m.on(LiveEvent.ListenAgain).outcome)
        // Only an explicit start resets it.
        assertEquals(TransitionOutcome.ACCEPTED, m.on(LiveEvent.Start).outcome)
        assertEquals(LiveStage.LISTENING, m.state.stage)
    }

    @Test
    fun `end returns to idle from any active stage`() {
        val m = LiveStateMachine()
        m.on(LiveEvent.Start)
        m.on(LiveEvent.TranscriptionReady)
        m.on(LiveEvent.GenerationStarted)
        assertEquals(TransitionOutcome.ACCEPTED, m.on(LiveEvent.End).outcome)
        assertEquals(LiveStage.IDLE, m.state.stage)
        assertFalse(m.state.isActive)
    }

    @Test
    fun `mute affects indicators not the flow`() {
        val m = LiveStateMachine()
        m.on(LiveEvent.Start)
        m.setMicMuted(true)
        assertFalse(m.state.listening)
        m.setMicMuted(false)
        assertTrue(m.state.listening)
    }

    @Test
    fun `interrupt is accepted from any active stage`() {
        val m = LiveStateMachine()
        m.on(LiveEvent.Start)
        assertEquals(LiveStage.LISTENING, m.state.stage)
        m.on(LiveEvent.Interrupt)
        assertEquals(LiveStage.INTERRUPTED, m.state.stage)
    }
}