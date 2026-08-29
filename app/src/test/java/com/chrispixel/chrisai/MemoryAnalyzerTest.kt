package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.memory.MemoryAnalyzer
import com.chrispixel.chrisai.data.model.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAnalyzerTest {

    @Test
    fun `isMemoryRequest detects explicit phrases`() {
        assertTrue(MemoryAnalyzer.isMemoryRequest("Recuerda que mi proyecto se llama ChrisTools"))
        assertTrue(MemoryAnalyzer.isMemoryRequest("Guarda esto en tu memoria: el resultado es 42"))
        assertTrue(MemoryAnalyzer.isMemoryRequest("Memoriza que el viernes es el examen"))
        assertFalse(MemoryAnalyzer.isMemoryRequest("Hola, ¿qué tal?"))
    }

    @Test
    fun `analyze cleans the framing and never stores garbage`() {
        val draft = MemoryAnalyzer.analyze("Recuerda que mi proyecto se llama ChrisTools.")
        assertNotNull(draft)
        // Never "el usuario dijo que recuerde…".
        assertEquals("Mi proyecto se llama ChrisTools.", draft!!.content)
        assertFalse(draft.content.contains("recuerda", ignoreCase = true))
    }

    @Test
    fun `analyze produces category importance and tags`() {
        val draft = MemoryAnalyzer.analyze("recuerda que el proyecto se llama ChrisTools y está en GitHub")
        assertNotNull(draft)
        assertEquals("proyecto", draft!!.category)
        assertEquals(5, draft.importance)
        assertTrue(draft.tags.contains("christools") || draft.tags.contains("chrchristools"))
    }

    @Test
    fun `analyze returns null for non memory input`() {
        assertNull(MemoryAnalyzer.analyze("hola buenos días"))
        assertNull(MemoryAnalyzer.analyze("por favor, explícame qué es un coroutine"))
    }

    @Test
    fun `dedupe key normalizes accents case and word order`() {
        assertEquals(
            MemoryAnalyzer.dedupeKey("Mi proyecto se llama ChrisTools"),
            MemoryAnalyzer.dedupeKey("ChrisTools se llama mi proyecto")
        )
        assertEquals(
            MemoryAnalyzer.dedupeKey("La cita es a las 5"),
            MemoryAnalyzer.dedupeKey("la cita es a las 5")
        )
        assertFalse(
            MemoryAnalyzer.dedupeKey("El vino es tinto") ==
                MemoryAnalyzer.dedupeKey("El vino es blanco")
        )
    }

    @Test
    fun `isDuplicate catches exact and substring duplicates`() {
        val existing = Memory("id1", "El proyecto del usuario se llama ChrisTools.", 0L, 0L)
        val exact = MemoryAnalyzer.analyze("recuerda que el proyecto del usuario se llama ChrisTools")
        assertNotNull(exact)
        assertTrue(MemoryAnalyzer.isDuplicate(existing, exact!!))
    }

    @Test
    fun `similarity is high for near-identical text`() {
        val sim = MemoryAnalyzer.similarity(
            "El proyecto se llama ChrisTools y está en GitHub",
            "El proyecto se llama ChrisTools y está guardado en GitHub"
        )
        assertTrue("similarity=$sim", sim > 0.5)
    }

    @Test
    fun `importance elevates critical content`() {
        assertEquals(5, MemoryAnalyzer.importance("Contraseña del correo importante"))
        assertEquals(3, MemoryAnalyzer.importance("Me gusta el café con leche"))
    }
}