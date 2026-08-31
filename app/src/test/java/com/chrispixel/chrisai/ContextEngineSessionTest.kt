package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.context.ContextEngine
import com.chrispixel.chrisai.data.context.SessionPrompts
import com.chrispixel.chrisai.data.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0 session contexts: per-session [SessionKind] framing goes through the
 * Context Engine as bounded system blocks, without touching legacy behavior.
 */
class ContextEngineSessionTest {

    private fun assemble(kind: SessionKind = SessionKind.DEFAULT, companionActive: Boolean = false) =
        ContextEngine.assemble(
            ContextEngine.Input(
                currentUserText = "hola",
                currentSessionMessages = emptyList(),
                relevantMemories = emptyList(),
                sessionKind = kind,
                companionActive = companionActive
            )
        )

    @Test
    fun `general sessions keep legacy context unchanged`() {
        val bundle = assemble(SessionKind.GENERAL)
        assertNull(bundle.sessionBlock)
        assertNull(bundle.studyBlock)
        assertNull(bundle.companionBlock)
    }

    @Test
    fun `study sessions inject the pedagogical contract exactly once`() {
        val bundle = assemble(SessionKind.STUDY)
        // STUDY contract comes through the study block (dedup with the toggle).
        assertNotNull(bundle.studyBlock)
        assertTrue(bundle.studyBlock!!.contains("MODO ESTUDIO"))
        assertNotNull(bundle.sessionBlock)
        assertTrue(bundle.sessionBlock!!.contains("SESIÓN DE ESTUDIO"))
    }

    @Test
    fun `study toggle and study session do not duplicate the contract`() {
        val toggled = assemble(SessionKind.GENERAL, companionActive = false)
            .let { ContextEngine.assemble(
                ContextEngine.Input(
                    currentUserText = "hola",
                    currentSessionMessages = emptyList(),
                    relevantMemories = emptyList(),
                    studyActive = true
                )
            ) }
        val fromSession = assemble(SessionKind.STUDY)
        assertEquals(toggled.studyBlock, fromSession.studyBlock)
    }

    @Test
    fun `programming sessions add a precise technical contract`() {
        val bundle = assemble(SessionKind.PROGRAMMING)
        assertNotNull(bundle.sessionBlock)
        assertTrue(bundle.sessionBlock!!.contains("SESIÓN DE PROGRAMACIÓN"))
        assertTrue(bundle.sessionBlock!!.contains("código"))
    }

    @Test
    fun `chrisai sessions add a lightweight project framing`() {
        val bundle = assemble(SessionKind.CHRISAI)
        assertNotNull(bundle.sessionBlock)
        assertTrue(bundle.sessionBlock!!.contains("PROYECTO CHRISAI"))
    }

    @Test
    fun `companion session activates the companion and session blocks`() {
        val bundle = assemble(SessionKind.COMPANION)
        assertNotNull(bundle.sessionBlock)
        assertTrue(bundle.sessionBlock!!.contains("ACOMPAÑANTE"))
        assertNotNull(bundle.companionBlock)
        assertTrue(bundle.companionBlock!!.contains("MODO ACOMPAÑANTE"))
    }

    @Test
    fun `system blocks are ordered and named deterministically`() {
        val blocks = ContextEngine.toSystemBlocks(assemble(SessionKind.PROGRAMMING))
        val names = blocks.map { it.first }
        val expected = listOf("CONTEXTO DE SESIÓN")
        assertEquals(expected, names)
    }

    @Test
    fun `session kind parser is tolerant and defaults to general`() {
        assertEquals(SessionKind.STUDY, SessionKind.fromId("study"))
        assertEquals(SessionKind.DEFAULT, SessionKind.fromId("unknown"))
        assertEquals(SessionKind.DEFAULT, SessionKind.fromId(null))
        assertEquals(SessionKind.CHRISAI, SessionKind.fromId(SessionKind.CHRISAI.id))
    }

    @Test
    fun `session prompts give no block for general`() {
        assertNull(SessionPrompts.block(SessionKind.GENERAL))
        assertNotNull(SessionPrompts.block(SessionKind.PROGRAMMING))
    }
}