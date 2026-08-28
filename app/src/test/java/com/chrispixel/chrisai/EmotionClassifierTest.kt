package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.emotion.EmotionClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class EmotionClassifierTest {

    @Test
    fun `treats blank as neutral`() {
        assertEquals(Emotion.NEUTRAL, EmotionClassifier.classify(""))
        assertEquals(Emotion.NEUTRAL, EmotionClassifier.classify("   "))
    }

    @Test
    fun `detects happy replies`() {
        assertEquals(Emotion.HAPPY, EmotionClassifier.classify("¡Genial, lo lograste! 👏"))
        assertEquals(Emotion.HAPPY, EmotionClassifier.classify("Excelente, me alegra mucho oírtelo."))
    }

    @Test
    fun `detects excited replies`() {
        assertEquals(Emotion.EXCITED, EmotionClassifier.classify("¡Increíble! Es impresionante 🔥"))
    }

    @Test
    fun `detects sad and empathetic replies`() {
        assertEquals(Emotion.SAD, EmotionClassifier.classify("Lo siento mucho, qué pena 😢"))
        assertEquals(Emotion.EMPATHETIC, EmotionClassifier.classify("Te entiendo y no estás solo."))
    }

    @Test
    fun `detects worried and thoughtful replies`() {
        assertEquals(Emotion.WORRIED, EmotionClassifier.classify("Me preocupa que no sea seguro, ten cuidado"))
        assertEquals(Emotion.THOUGHTFUL, EmotionClassifier.classify("Déjame pensar, tal vez podría ser así 🤔"))
    }

    @Test
    fun `detects surprised replies`() {
        assertEquals(Emotion.SURPRISED, EmotionClassifier.classify("¡Vaya, no me lo esperaba! 🤯"))
    }

    @Test
    fun `detects angry replies`() {
        assertEquals(Emotion.ANGRY, EmotionClassifier.classify("Me enfada, es inaceptable 😡"))
    }

    @Test
    fun `long replies only look at the beginning`() {
        val long = "Genial, perfecto.\n" + "texto sin emociones ".repeat(300)
        assertEquals(Emotion.HAPPY, EmotionClassifier.classify(long))
    }

    @Test
    fun `no signal maps to neutral`() {
        assertEquals(Emotion.NEUTRAL, EmotionClassifier.classify("La capital de Francia es París"))
        assertEquals(Emotion.NEUTRAL, EmotionClassifier.classify("El código compila sin errores."))
    }

    @Test
    fun `generating is not returned by the classifier`() {
        assertEquals(Emotion.NEUTRAL, EmotionClassifier.classify("¿Generando?"))
        // GENERATING only exists as a transitional state in the ViewModel.
        assert(Emotion.GENERATING.accent.value != Emotion.NEUTRAL.accent.value)
    }
}