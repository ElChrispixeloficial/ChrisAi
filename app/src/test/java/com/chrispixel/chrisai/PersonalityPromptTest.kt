package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.personality.PersonalityConfig
import com.chrispixel.chrisai.data.personality.PersonalityPreset
import com.chrispixel.chrisai.data.personality.PersonalityPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalityPromptTest {

    @Test
    fun `default config uses casual preset and sane levels`() {
        val config = PersonalityConfig()
        assertEquals("casual", config.presetId)
        assertEquals(PersonalityPreset.CASUAL, config.preset)
        assertTrue(config.humorLevel in 1..5)
        assertTrue(config.detailLevel in 1..5)
    }

    @Test
    fun `preset lookup falls back to casual for unknown id`() {
        assertEquals(PersonalityPreset.CASUAL, PersonalityPreset.byId("no-existe"))
        assertEquals(PersonalityPreset.TUTOR, PersonalityPreset.byId("tutor"))
        assertEquals(6, PersonalityPreset.all().size)
    }

    @Test
    fun `block includes fixed safety note`() {
        val block = PersonalityPrompt.block(PersonalityConfig())
        assertTrue(block.contains("[PERSONALIDAD]"))
        assertTrue(block.contains("Nombre: ChrisAI"))
        assertTrue(block.contains("prioridad máxima"))
        assertTrue(block.contains("reglas de seguridad"))
    }

    @Test
    fun `custom values reflected and encoded`() {
        val config = PersonalityConfig(
            name = "   Rob   ",
            presetId = "energetico",
            humorLevel = 4,
            detailLevel = 3,
            communicationStyle = "motivadora y clara",
            customInstructions = "Nunca uses jerga."
        )
        val block = PersonalityPrompt.block(config)
        assertTrue(block.contains("Nombre: Rob"))
        assertTrue(block.contains("Tono: entusiasta y dinámica"))
        assertTrue(block.contains("Nivel de humor: 4/5"))
        assertTrue(block.contains("Nivel de detalle: 3/5"))
        assertTrue(block.contains("Estilo de comunicación: motivadora y clara"))
        assertTrue(block.contains("Nunca uses jerga."))
    }

    @Test
    fun `long fields are truncated to limits`() {
        val config = PersonalityConfig(
            name = "A".repeat(200),
            communicationStyle = "B".repeat(500),
            customInstructions = "C".repeat(2000)
        )
        val block = PersonalityPrompt.block(config)
        val name = config.name.trim().take(PersonalityPrompt.MAX_NAME_CHARS)
        assertEquals(30, name.length)
        assertTrue(block.contains("Nombre: ${"A".repeat(30)}"))
        assertTrue("style should be capped", !block.contains("B".repeat(121)))
        assertTrue("instructions should be capped", !block.contains("C".repeat(801)))
        assertTrue(config.customInstructions.take(PersonalityPrompt.MAX_INSTRUCTIONS_CHARS).length == 800)
    }

    @Test
    fun `empty custom style falls back to preset default`() {
        val block = PersonalityPrompt.block(
            PersonalityConfig(presetId = "minimalista", communicationStyle = "   ")
        )
        assertTrue(block.contains("conciso y sin relleno"))
    }

    @Test
    fun `validation rejects out of range levels`() {
        try {
            PersonalityConfig(humorLevel = 0)
            throw AssertionError("humorLevel=0 should throw")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        try {
            PersonalityConfig(detailLevel = 6)
            throw AssertionError("detailLevel=6 should throw")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("1..5"))
        }
    }

    @Test
    fun `isWhitespaceOnly detects all blank config`() {
        assertTrue(PersonalityPrompt.isWhitespaceOnly(PersonalityConfig(name = "", customInstructions = "", communicationStyle = "")))
        assertFalse(PersonalityPrompt.isWhitespaceOnly(PersonalityConfig(name = "Chris")))
    }
}