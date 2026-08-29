package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.speech.TtsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextTest {

    @Test
    fun `emojis are removed and spaces normalized`() {
        val out = TtsText.prepare("Hola 😀 cómo estás 🚀🔥❤️")
        assertFalse(out.contains("😀"))
        assertFalse(out.contains("🚀"))
        assertFalse(out.contains("❤️"))
        assertEquals("Hola cómo estás", out)
    }

    @Test
    fun `markdown markers are stripped`() {
        assertEquals("Esto es texto enlace", TtsText.prepare("**Esto** __es__ `texto` [enlace](https://x.com)"))
        assertFalse(TtsText.prepare("# Título").contains("#"))
        assertFalse(TtsText.prepare("~~rayado~~").contains("~~"))
    }

    @Test
    fun `urls become spoken form`() {
        val out = TtsText.prepare("Visita https://example.com ahora.")
        assertFalse(out.contains("https"))
        assertEquals("Visita web example punto com ahora.", out)
    }

    @Test
    fun `percentages and abbreviations become words`() {
        assertEquals("está al veinte por ciento", TtsText.prepare("está al 20%"))
        assertTrue(TtsText.prepare("p.ej. algo").lowercase().contains("por ejemplo"))
    }

    @Test
    fun `currency becomes words`() {
        assertEquals("Cuesta cinco euros.", TtsText.prepare("Cuesta 5€."))
        assertEquals("Cuesta cinco dólares.", TtsText.prepare("Cuesta 5 USD."))
    }

    @Test
    fun `numbers become words in spanish`() {
        assertEquals("Son las tres", TtsText.prepare("Son las 3"))
        assertEquals("Tengo veinticinco años", TtsText.prepare("Tengo 25 años"))
        assertEquals("Son ciento veintitrés", TtsText.prepare("Son 123"))
        assertEquals("Son cien", TtsText.prepare("Son 100"))
        assertEquals(
            "El valor es tres coma uno cuatro",
            TtsText.prepare("El valor es 3.14")
        )
    }

    @Test
    fun `symbols become words`() {
        assertFalse(TtsText.prepare("a & b").contains("&"))
        assertEquals("a y b", TtsText.prepare("a & b"))
        assertEquals("hace treinta grados Celsius", TtsText.prepare("hace 30°C"))
    }

    @Test
    fun `ellipsis and repeated spaces are normalized`() {
        assertFalse(TtsText.prepare("Hola…").contains("…"))
        assertEquals("Hola hola", TtsText.prepare("Hola    hola"))
        assertFalse(TtsText.prepare("Hola    mundo").contains("    "))
    }

    @Test
    fun `blank input is blank`() {
        assertEquals("", TtsText.prepare(""))
        assertEquals("", TtsText.prepare("   "))
    }

    @Test
    fun `code fences are removed`() {
        val out = TtsText.prepare("```kotlin\nval x = 1\n```\nFin.")
        assertFalse(out.contains("```"))
        assertTrue(out.contains("Fin"))
    }

    @Test
    fun `ordered and bullet lists lose their markers`() {
        assertTrue(TtsText.prepare("- uno\n- dos\n1. tres").lowercase().contains("uno"))
        assertFalse(TtsText.prepare("- uno").contains("-"))
    }

    @Test
    fun `cleaned output never contains placeholder brackets`() {
        val out = TtsText.prepare("[TOOLS] no aparece aquí")
        assertFalse(out.contains("[TOOLS]"))
    }
}