package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.local.MemoryIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryIntentTest {

    @Test
    fun `recognizes explicit save commands`() {
        assertEquals("mañana es mi cita", MemoryIntent.saveText("recuerda que mañana es mi cita"))
        assertEquals("comprar pan", MemoryIntent.saveText("acuérdate de comprar pan"))
        assertEquals("el Código A-7", MemoryIntent.saveText("memoriza el Código A-7"))
        assertEquals("vengo mañana", MemoryIntent.saveText("recuerda que vengo mañana"))
        assertEquals("mi color favorito es azul", MemoryIntent.saveText("Acuerdate que mi color favorito es azul"))
    }

    @Test
    fun `does not hijack normal chat`() {
        assertNull(MemoryIntent.saveText("Hola"))
        assertNull(MemoryIntent.saveText("Recuerdas mi cumpleaños?"))
        assertNull(MemoryIntent.saveText("Me recuerdas a alguien"))
        assertNull(MemoryIntent.saveText("recuerda que"))
        assertNull(MemoryIntent.saveText("no"))
    }

    @Test
    fun `recognizes forget commands`() {
        assertEquals("me llamo X", MemoryIntent.forgetText("olvida que me llamo X"))
        assertEquals("pimienta", MemoryIntent.forgetText("olvida pimienta"))
        assertEquals("pimienta", MemoryIntent.forgetText("borra de tu memoria pimienta"))
        assertNull(MemoryIntent.forgetText("olvido algo"))
    }

    @Test
    fun `recognizes forget-all and listing`() {
        assertTrue(MemoryIntent.forgetAll("olvida todo"))
        assertTrue(MemoryIntent.forgetAll("Borra todos tus recuerdos"))
        assertFalse(MemoryIntent.forgetAll("olvida pan"))
        assertTrue(MemoryIntent.listRequested("¿Qué recuerdas?"))
        assertTrue(MemoryIntent.listRequested("muéstrame tus recuerdos"))
        assertFalse(MemoryIntent.listRequested("cuéntame un chiste"))
    }

    @Test
    fun `parses model memory tags and strips them from display`() {
        val result = MemoryIntent.parseTags(
            "Claro.\n[MEMORIA: el usuario es alérgico a los frutos secos]\nMañana te recuerdo tu cita.\n[OLVIDA: la receta de paella]\n"
        )
        assertEquals(listOf("el usuario es alérgico a los frutos secos"), result.toSave)
        assertEquals(listOf("la receta de paella"), result.toForget)
        assertFalse(result.cleaned.contains("[MEMORIA"))
        assertFalse(result.cleaned.contains("[OLVIDA"))
        assertTrue(result.cleaned.contains("Claro"))
        assertTrue(result.cleaned.contains("Mañana te recuerdo tu cita"))
    }

    @Test
    fun `parses empty content safely`() {
        val result = MemoryIntent.parseTags("")
        assertEquals(emptyList<String>(), result.toSave)
        assertEquals("", result.cleaned)
    }
}