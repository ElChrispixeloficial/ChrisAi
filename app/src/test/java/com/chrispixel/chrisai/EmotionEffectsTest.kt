package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.emotion.EmotionEffects
import com.chrispixel.chrisai.data.emotion.EmotionExpression
import com.chrispixel.chrisai.data.emotion.EmotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionEffectsTest {

    @Test
    fun `exactly one primary effect per emotion`() {
        for (emotion in Emotion.entries) {
            val effect = EmotionEffects.primary(emotion)
            assertEquals(emotion, effect.emotion)
            assertTrue(effect.name.isNotBlank())
            assertTrue(effect.accentName.isNotBlank())
        }
        assertEquals(Emotion.entries.size, EmotionEffects.effects.size)
    }

    @Test
    fun `every state is computational, never a claim of feeling`() {
        val state = EmotionState(type = Emotion.HAPPY, intensity = 0.9f)
        assertTrue(EmotionExpression.isComputational(state))
    }

    @Test
    fun `state phrase is honest and intensity-aware`() {
        assertEquals(
            "estado prioritario alta: feliz",
            EmotionExpression.statePhrase(EmotionState(type = Emotion.HAPPY, intensity = 0.9f))
        )
        assertEquals(
            "estado prioritario moderada: pensativo",
            EmotionExpression.statePhrase(EmotionState(type = Emotion.THOUGHTFUL, intensity = 0.6f))
        )
        assertEquals(
            "estado prioritario leve: triste",
            EmotionExpression.statePhrase(EmotionState(type = Emotion.SAD, intensity = 0.3f))
        )
    }

    @Test
    fun `neutral and generating never narrated`() {
        assertNull(EmotionExpression.statePhrase(EmotionState(type = Emotion.NEUTRAL)))
        assertNull(EmotionExpression.statePhrase(EmotionState(type = Emotion.GENERATING)))
    }
}