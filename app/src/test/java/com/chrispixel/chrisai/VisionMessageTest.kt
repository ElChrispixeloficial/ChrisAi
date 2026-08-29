package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.vision.VisionMessage
import com.chrispixel.chrisai.data.vision.VisionSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionMessageTest {

    @Test
    fun `builds a valid multimodal message`() {
        val json = VisionMessage.buildUserMessage("QUJDRA==", "image/png", "¿Qué aparece aquí?")
        assertTrue(VisionMessage.isValidVisionMessage(json))
        assertTrue(json.contains("data:image/png;base64,QUJDRA=="))
    }

    @Test
    fun `defaults mime to jpeg when missing`() {
        val json = VisionMessage.buildUserMessage("QUJDRA==", null, "describe")
        assertTrue(json.contains("data:image/jpeg;base64,QUJDRA=="))
    }

    @Test
    fun `plain text message is not a vision message`() {
        assertFalse(VisionMessage.isValidVisionMessage("[]"))
        assertFalse(VisionMessage.isValidVisionMessage("{\"role\":\"user\",\"content\":\"hola\"}"))
    }

    @Test
    fun `size guard rejects huge images`() {
        assertFalse(VisionMessage.isReasonableImageSize(0))
        assertTrue(VisionMessage.isReasonableImageSize(1024))
        assertFalse(VisionMessage.isReasonableImageSize(10_000_000))
    }

    @Test
    fun `vision capable models are recognized`() {
        assertEquals(VisionSupport.LIKELY_SUPPORTED, VisionMessage.support("google/gemini-2.0-flash"))
        assertEquals(VisionSupport.LIKELY_SUPPORTED, VisionMessage.support("openai/gpt-4o-mini"))
        assertEquals(VisionSupport.LIKELY_SUPPORTED, VisionMessage.support("meta-llama/llama-3.2-90b-vision-instruct"))
        assertEquals(VisionSupport.LIKELY_SUPPORTED, VisionMessage.support("anthropic/claude-3.5-sonnet"))
    }

    @Test
    fun `text only models are flagged`() {
        assertEquals(VisionSupport.NOT_SUPPORTED, VisionMessage.support("openai/gpt-3.5-turbo"))
        assertEquals(VisionSupport.NOT_SUPPORTED, VisionMessage.support("deepseek/deepseek-chat-v3"))
    }

    @Test
    fun `unknown models are attempted`() {
        assertEquals(VisionSupport.UNKNOWN, VisionMessage.support("virutal/default"))
        assertEquals(VisionSupport.UNKNOWN, VisionMessage.support(""))
    }
}