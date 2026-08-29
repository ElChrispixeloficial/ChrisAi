package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.tools.Tool
import com.chrispixel.chrisai.data.tools.ToolCall
import com.chrispixel.chrisai.data.tools.ToolCallParser
import com.chrispixel.chrisai.data.tools.ToolParam
import com.chrispixel.chrisai.data.tools.ToolRegistry
import com.chrispixel.chrisai.data.tools.ToolResult
import com.chrispixel.chrisai.data.tools.ToolResultStatus
import com.chrispixel.chrisai.data.tools.ToolRiskLevel
import com.chrispixel.chrisai.data.tools.android.AppMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    // ------------------------------------------------------------------ parser

    @Test
    fun `parses a single tool call and keeps the preamble`() {
        val text = "Claro, te la abro.\n[TOOLS]\n" +
            "{\"calls\":[{\"id\":\"open_app\",\"arguments\":{\"appName\":\"YouTube\"}}]}\n" +
            "[/TOOLS]"
        val block = ToolCallParser.parse(text)
        assertTrue(block != null)
        assertEquals("Claro, te la abro.", block!!.preamble)
        assertEquals(1, block.calls.size)
        assertEquals("open_app", block.calls[0].id)
        assertEquals("YouTube", block.calls[0].arguments["appName"])
    }

    @Test
    fun `parses chained calls`() {
        val text = "[TOOLS]\n" +
            "{\"calls\":[{\"id\":\"create_timer\",\"arguments\":{\"durationSeconds\":\"300\"}}," +
            "{\"id\":\"show_notification\",\"arguments\":{\"title\":\"Aviso\",\"message\":\"Listo\"}}]}\n" +
            "[/TOOLS]"
        val block = ToolCallParser.parse(text)
        assertTrue(block != null)
        assertEquals(2, block!!.calls.size)
        assertEquals("300", block.calls[0].arguments["durationSeconds"])
    }

    @Test
    fun `accepts wrapped json fences`() {
        val text = "Hago esto.\n[TOOLS]\n```json\n" +
            "{\"calls\":[{\"id\":\"get_time\",\"arguments\":{}}]}\n```\n[/TOOLS]"
        val block = ToolCallParser.parse(text)
        assertTrue(block != null)
        assertEquals("get_time", block!!.calls[0].id)
    }

    @Test
    fun `returns null when there is no envelope`() {
        assertNull(ToolCallParser.parse("Hola, ¿en qué te ayudo?"))
        assertNull(ToolCallParser.parse("Hablamos sobre [TOOLS] o no"))
    }

    @Test
    fun `returns null on malformed json`() {
        assertNull(ToolCallParser.parse("[TOOLS]esto no es json[/TOOLS]"))
    }

    @Test
    fun `visibleText strips everything at the marker`() {
        val text = "Respuesta legible.\n[TOOLS]\n{\"calls\":[]}\n[/TOOLS]"
        assertEquals("Respuesta legible.", ToolCallParser.visibleText(text))
        assertEquals("Solo texto", ToolCallParser.visibleText("Solo texto"))
    }

    // ------------------------------------------------------------------ matcher

    private val pool = listOf(
        AppMatcher.App("YouTube", "com.google.android.youtube"),
        AppMatcher.App("Música", "com.google.android.apps.youtube.music"),
        AppMatcher.App("Super Bear Adventure", "com.robtix.superbear")
    )

    @Test
    fun `partial case-insensitive match by label`() {
        val out = AppMatcher.search(pool, "yout", limit = 8)
        assertEquals(2, out.size)
        assertEquals("YouTube", out[0].label)
    }

    @Test
    fun `package name match works too`() {
        val out = AppMatcher.search(pool, "robtix", limit = 8)
        assertEquals(1, out.size)
        assertEquals("Super Bear Adventure", out[0].label)
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(AppMatcher.search(pool, "wezzt", limit = 8).isEmpty())
    }

    @Test
    fun `multiple matches are ranked by relevance`() {
        val out = AppMatcher.search(pool, "musica", limit = 8)
        assertEquals(1, out.size)
        assertEquals("Música", out[0].label)
    }

    @Test
    fun `empty query lists everything up to the limit`() {
        val out = AppMatcher.search(pool, "", limit = 2)
        assertEquals(2, out.size)
    }

    // ------------------------------------------------------------------ registry

    private class FakeTool(
        override val id: String = "fake_tool",
        override val requiresConfirmation: Boolean = false,
        override val requiresShizuku: Boolean = false,
        private val handler: (Map<String, String>) -> ToolResult = {
            ToolResult(ToolResultStatus.SUCCESS, id, "ok: ${it["x"]}")
        }
    ) : Tool {
        override val name = "Herramienta de prueba"
        override val description = "Solo para tests."
        override val parameters = listOf(ToolParam("x", "string", "Valor de prueba"))
        override val permissions = emptyList<String>()
        override val risk = ToolRiskLevel.SAFE
        override suspend fun execute(args: Map<String, String>): ToolResult = handler(args)
    }

    @Test
    fun `executes a known tool and drops undeclared args`() = runBlocking {
        val registry = ToolRegistry(listOf(FakeTool()))
        val result = registry.execute(
            ToolCall("fake_tool", mapOf("x" to "hola", "injected" to "malicioso"))
        )
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("ok: hola", result.message)
    }

    @Test
    fun `unknown tool yields not found`() = runBlocking {
        val registry = ToolRegistry(listOf(FakeTool()))
        val result = registry.execute(ToolCall("open_app", mapOf("appName" to "X")))
        assertEquals(ToolResultStatus.NOT_FOUND, result.status)
    }

    @Test
    fun `confirmation requirement is enforced and honored`() = runBlocking {
        val registry = ToolRegistry(listOf(FakeTool(requiresConfirmation = true)))
        val first = registry.execute(ToolCall("fake_tool", mapOf("x" to "1")))
        assertEquals(ToolResultStatus.REQUIRES_CONFIRMATION, first.status)
        assertTrue(first.needsConfirmation)
        val token = first.confirmationToken
        assertTrue(!token.isNullOrBlank())
        val second = registry.execute(ToolCall("fake_tool", mapOf("x" to "1")), desiredToken = token)
        assertEquals(ToolResultStatus.SUCCESS, second.status)
    }

    @Test
    fun `shizuku-required tool is refused without shizuku`() = runBlocking {
        val registry = ToolRegistry(listOf(FakeTool(requiresShizuku = true)))
        registry.shizukuAvailable = false
        val result = registry.execute(ToolCall("fake_tool", mapOf("x" to "1")))
        assertEquals(ToolResultStatus.REQUIRES_SHIZUKU, result.status)
    }

    @Test
    fun `shizuku tool runs when shizuku is available`() = runBlocking {
        val registry = ToolRegistry(listOf(FakeTool(requiresShizuku = true)))
        registry.shizukuAvailable = true
        val result = registry.execute(ToolCall("fake_tool", mapOf("x" to "1")))
        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun `describe mentions each tool id`() {
        val registry = ToolRegistry(listOf(FakeTool(), FakeTool(id = "get_time")))
        val text = registry.describe()
        assertTrue(text.contains("fake_tool"))
        assertTrue(text.contains("get_time"))
        assertTrue(text.contains("[TOOLS]"))
    }
}