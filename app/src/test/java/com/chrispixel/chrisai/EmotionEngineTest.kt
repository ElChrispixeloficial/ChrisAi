package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.emotion.EmotionEngine
import com.chrispixel.chrisai.data.emotion.EmotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionEngineTest {

    @Test
    fun `neutral and generating have the expected shape`() {
        assertEquals(Emotion.NEUTRAL, EmotionEngine.neutral().type)
        assertEquals(0f, EmotionEngine.neutral().intensity, 0f)
        assertEquals(Emotion.GENERATING, EmotionEngine.generating().type)
        assertTrue(EmotionEngine.generating().intensity > 0.5f)
    }

    @Test
    fun `classifyUser maps positive messages and loud text`() {
        assertEquals(Emotion.HAPPY, EmotionEngine.classifyUser("me alegra mucho"))
        // "!!"/ALL-CAPS escalate a HAPPY message into EXCITED.
        assertEquals(Emotion.EXCITED, EmotionEngine.classifyUser("me alegra mucho!!"))
        assertEquals(Emotion.EXCITED, EmotionEngine.classifyUser("¡INCREÍBLE FUTURO!"))
        assertEquals(Emotion.SAD, EmotionEngine.classifyUser("estoy muy triste"))
        assertEquals(Emotion.NEUTRAL, EmotionEngine.classifyUser("¿qué hora es?"))
    }

    @Test
    fun `finalState detects reply emotions with intensity in buckets`() {
        val state = EmotionEngine.finalState(
            userText = "hola",
            replyText = "¡Genial, lo lograste! 👏",
            toolSucceeded = null,
            previous = null
        )
        assertEquals(Emotion.HAPPY, state.type)
        assertTrue(state.intensity in setOf(0.25f, 0.5f, 0.75f, 1f))
        assertTrue(state.confidence > 0f)
    }

    @Test
    fun `finalState is deterministic and resets calmly on neutral replies`() {
        val previous = EmotionState(
            type = Emotion.HAPPY,
            intensity = 0.5f,
            confidence = 0.6f
        )
        val state = EmotionEngine.finalState(
            userText = "vale",
            replyText = "Entendido.",
            toolSucceeded = null,
            previous = previous
        )
        // No emotional signal: the state returns to NEUTRAL, never stays loud.
        assertEquals(Emotion.NEUTRAL, state.type)
        // Calling it again with its own output yields the same stable state.
        val again = EmotionEngine.finalState(
            userText = "¿y luego qué?",
            replyText = "El resultado es 42.",
            toolSucceeded = null,
            previous = state
        )
        assertEquals(Emotion.NEUTRAL, again.type)
    }

    @Test
    fun `neutral replies map to neutral unless user is distressed`() {
        val state = EmotionEngine.finalState(
            userText = "¿qué es kotlin?",
            replyText = "Kotlin es un lenguaje de programación.",
            toolSucceeded = null,
            previous = null
        )
        assertEquals(Emotion.NEUTRAL, state.type)
    }

    @Test
    fun `tool success nudges toward happy subtle`() {
        val state = EmotionEngine.finalState(
            userText = "abre youtube",
            replyText = "Listo, ya la abrí.",
            toolSucceeded = true,
            previous = null
        )
        assertTrue(state.type == Emotion.HAPPY || state.type == Emotion.NEUTRAL)
        assertTrue(state.intensity <= 0.5f)
    }

    @Test
    fun `tool failure flags worried without exaggeration`() {
        val state = EmotionEngine.finalState(
            userText = "abre xyz",
            replyText = "No encontré esa aplicación.",
            toolSucceeded = false,
            previous = null
        )
        assertEquals(Emotion.WORRIED, state.type)
        assertTrue(state.intensity <= 0.75f)
    }

    @Test
    fun `toolOutcomeEmotion stays subtle`() {
        assertEquals(Emotion.HAPPY, EmotionEngine.toolOutcomeEmotion(true).type)
        assertEquals(Emotion.WORRIED, EmotionEngine.toolOutcomeEmotion(false).type)
        assertTrue(EmotionEngine.toolOutcomeEmotion(true).intensity < 0.5f)
    }

    @Test
    fun `intensidad is bucketed to the spec values`() {
        val strong = EmotionEngine.finalState(
            userText = "!!!",
            replyText = "¡¡Vaya!! ¡¡Increíble!! ¡No me lo esperaba!",
            toolSucceeded = null,
            previous = null
        )
        assertTrue(strong.intensity in setOf(0.25f, 0.5f, 0.75f, 1f))
    }

    @Test
    fun `generating is a transient state with priority`() {
        assertEquals(Emotion.GENERATING, EmotionEngine.generating().type)
        // After finishing, the engine never returns GENERATING as a final mood.
        val final = EmotionEngine.finalState(
            userText = "hola",
            replyText = "Hola, ¿en qué te ayudo?",
            toolSucceeded = null,
            previous = EmotionEngine.generating()
        )
        assertTrue(final.type != Emotion.GENERATING)
    }
}