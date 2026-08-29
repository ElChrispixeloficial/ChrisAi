package com.chrispixel.chrisai.data

import com.chrispixel.chrisai.data.local.ChatStore
import com.chrispixel.chrisai.data.local.MemoryIntent
import com.chrispixel.chrisai.data.local.MemoryStore
import com.chrispixel.chrisai.data.model.AiModel
import com.chrispixel.chrisai.data.model.ChatRole
import com.chrispixel.chrisai.data.model.ChatSession
import com.chrispixel.chrisai.data.model.Memory
import com.chrispixel.chrisai.data.personality.PersonalityPrompt
import com.chrispixel.chrisai.data.remote.OpenRouterApi
import com.chrispixel.chrisai.data.tools.ToolCallParser
import com.chrispixel.chrisai.data.tools.ToolExecutionReport
import com.chrispixel.chrisai.data.tools.ToolResultStatus
import com.chrispixel.chrisai.data.tools.android.ToolEvent
import com.chrispixel.chrisai.data.tools.android.ToolManager

data class StreamReply(
    val text: String,
    val latencyMs: Long?,
    val totalMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    // v0.7 ChrisTools: live indicator events + aggregate result.
    val toolEvents: List<ToolEvent> = emptyList(),
    val toolSucceeded: Boolean = false,
    val toolCallCount: Int = 0
)

/**
 * Orchestrates requests to OpenRouter: builds the payload with clearly separated
 * blocks (security -> tools -> personality -> emotion context -> memory context ->
 * trimmed history) and streams the assistant reply, measuring latency and tokens.
 *
 * v0.7: when the model emits a [TOOLS] envelope at the end of its text, the block
 * is parsed, validated and executed through [ToolManager] (never fallible,
 * arbitrary code), the visible preamble is preserved, and a second model pass
 * produces the final answer informed by the real execution report.
 */
class ChatRepository(
    private val api: OpenRouterApi,
    private val chatStore: ChatStore,
    private val settings: SettingsRepository,
    private val memory: MemoryStore,
    private val tools: ToolManager
) {

    private val apiKey get() = settings.apiKey.value

    /** Streams a reply for [session]. Returns the cleaned text plus metrics. */
    suspend fun streamReply(
        session: ChatSession,
        onDelta: (String) -> Unit,
        emotionContext: String? = null
    ): StreamReply {
        val latestUser = session.messages.lastOrNull { it.role == ChatRole.USER }?.content
        val relevantMemories = relevantMemories(latestUser)

        val basePayload = buildPayload(session, latestUser, relevantMemories, emotionContext)

        // Round 1: stream through a filter that hides any [TOOLS] envelope live.
        val roundOne = VisibleFilter(onDelta)
        val first = api.streamChat(
            messages = basePayload,
            model = session.model.ifBlank { settings.model.value },
            apiKey = apiKey,
            temperature = settings.temperature.value,
            onDelta = roundOne::accept
        )

        val block = ToolCallParser.parse(first.text)
        val wantsTools = block?.calls?.isNotEmpty() == true

        var finalText: String
        var totalLatency = first.latencyMs
        var totalTotal = first.totalMs
        var totalPrompt = first.promptTokens
        var totalCompletion = first.completionTokens
        val toolEvents = mutableListOf<ToolEvent>()

        if (!wantsTools || block == null) {
            // Assistant answer without tool calls (or a stray marker).
            finalText = ToolCallParser.visibleText(first.text)
        } else {
            finalText = block.preamble
            val report = tools.execute(block) { toolEvents += it }
            if (report != null) {
                val roundTwoPayload = buildRoundTwoPayload(basePayload, block, report)
                val roundTwo = api.streamChat(
                    messages = roundTwoPayload,
                    model = session.model.ifBlank { settings.model.value },
                    apiKey = apiKey,
                    temperature = settings.temperature.value,
                    onDelta = onDelta
                )
                val finalVisible = ToolCallParser.visibleText(roundTwo.text)
                if (finalVisible.isNotBlank()) finalText = finalVisible
                totalLatency = sumNullable(totalLatency, roundTwo.latencyMs)
                totalTotal = sumNullable(totalTotal, roundTwo.totalMs)
                totalPrompt = sumNullable(totalPrompt, roundTwo.promptTokens)
                totalCompletion = sumNullable(totalCompletion, roundTwo.completionTokens)
            }
        }

        val tagged = MemoryIntent.parseTags(finalText)
        tagged.toSave.forEach { memory.add(it) }
        tagged.toForget.forEach { memory.removeContaining(it) }

        return StreamReply(
            text = tagged.cleaned,
            latencyMs = totalLatency,
            totalMs = totalTotal,
            promptTokens = totalPrompt,
            completionTokens = totalCompletion,
            toolEvents = toolEvents,
            toolSucceeded = toolEvents.any { it.status == ToolResultStatus.SUCCESS },
            toolCallCount = toolEvents.count { it.status != ToolResultStatus.RUNNING }
        )
    }

    /** System/Safety -> tools schemas -> personality -> emotion -> memory -> history. */
    private fun buildPayload(
        session: ChatSession,
        latestUser: String?,
        relevantMemories: List<Memory>,
        emotionContext: String?
    ): List<Map<String, Any>> = buildList {
        add(mapOf("role" to "system", "content" to Prompts.SYSTEM_PROMPT))
        add(mapOf("role" to "system", "content" to tools.schemas()))
        add(mapOf("role" to "system", "content" to PersonalityPrompt.block(settings.personality.value)))
        emotionContext?.takeIf { it.isNotBlank() }?.let {
            add(mapOf("role" to "system", "content" to it))
        }
        add(mapOf("role" to "system", "content" to memory.asContext(relevantMemories)))
        val trimmed = session.messages
            .takeLast(MAX_MESSAGES)
            .filter { it.role != ChatRole.SYSTEM }
        val lastUserIndex = trimmed.indexOfLast { it.role == ChatRole.USER }
        trimmed.forEachIndexed { index, message ->
            val content: Any = if (
                message.role == ChatRole.USER &&
                message.imagePath != null &&
                index == lastUserIndex
            ) {
                visionContent(message)
                    ?: redact(trim(message.content))
            } else {
                redact(trim(message.content))
            }
            add(mapOf("role" to message.role.apiValue, "content" to content))
        }
    }

    /**
     * Builds a multimodal content array (text + data-URI image) for the image
     * message. Returns null when the file is missing, too large, or unreadable
     * so the caller falls back to a plain-text caption.
     */
    private fun visionContent(message: com.chrispixel.chrisai.data.model.ChatMessage): Any? {
        val path = message.imagePath ?: return null
        val file = java.io.File(path)
        if (!file.exists() || !file.isFile) return null
        if (!com.chrispixel.chrisai.data.vision.VisionMessage.isReasonableImageSize(file.length().toInt())) return null
        return try {
            val bytes = file.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val prompt = message.content.ifBlank { "Analiza esta imagen y descríbela." }
            com.chrispixel.chrisai.data.vision.VisionMessage.userContentArray(
                base64Image = base64,
                mimeType = mimeTypeOf(path),
                prompt = prompt
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    /** Informs the model of the real outcome before asking for the final answer. */
    private fun buildRoundTwoPayload(
        base: List<Map<String, Any>>,
        block: com.chrispixel.chrisai.data.tools.ToolCallBlock,
        report: ToolExecutionReport
    ): List<Map<String, Any>> = base + listOf(
        mapOf("role" to "assistant", "content" to block.preamble.ifBlank { "Ejecuto la acción." }),
        mapOf(
            "role" to "user",
            "content" to "He ejecutado las siguientes acciones (resultado real):\n" +
                report.summaryForModel() +
                "\n\nEscribe ahora el mensaje final para el usuario con el resultado. " +
                "No repitas el bloque [TOOLS] ni la sintaxis de herramientas."
        )
    )

    private fun sumNullable(a: Long?, b: Long?): Long? = when {
        a == null && b == null -> null
        else -> (a ?: 0L) + (b ?: 0L)
    }

    private fun sumNullable(a: Int?, b: Int?): Int? = when {
        a == null && b == null -> null
        else -> (a ?: 0) + (b ?: 0)
    }

    /** Forwards streamed text to the UI but suppresses everything at/after [TOOLS]. */
    private class VisibleFilter(private val next: (String) -> Unit) {
        private val buffer = StringBuilder()
        private var cursor = 0
        private var suppressed = false

        fun accept(chunk: String) {
            if (suppressed) return
            buffer.append(chunk)
            val marker = buffer.indexOf(TOOLS_MARKER, cursor)
            if (marker >= 0) {
                if (marker > cursor) next(buffer.substring(cursor, marker))
                cursor = buffer.length
                suppressed = true
            } else {
                next(buffer.substring(cursor))
                cursor = buffer.length
            }
        }

        private companion object {
            const val TOOLS_MARKER = "[TOOLS]"
        }
    }

    private suspend fun relevantMemories(userText: String?): List<Memory> {
        val terms = userText?.let(::queryTerms).orEmpty()
        val candidates = memory.search(terms)
        if (terms.isEmpty()) return candidates.takeLast(MAX_MEMORIES)
        return candidates
            .sortedByDescending { memoryItem -> terms.count { it in memoryItem.text.lowercase() } }
            .take(MAX_MEMORIES)
    }

    private fun queryTerms(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 3 }
            .filterNot { it in STOP_WORDS }
            .distinct()
            .take(MAX_TERMS)

    /** Blocks any API-key material (the bundled key or a typed one) from leaving. */
    private fun redact(content: String): String {
        if (content.contains("[redactado]")) return content
        var out = content
        if (apiKey.isNotBlank()) out = out.replace(apiKey, "[redactado]")
        out = KEY_PATTERN.replace(out, "[redactado]")
        return out
    }

    private fun trim(content: String): String =
        if (content.length > MAX_MESSAGE_CHARS) content.take(MAX_MESSAGE_CHARS) else content

    suspend fun fetchModels(): List<AiModel> = api.listModels(apiKey)

    suspend fun saveSession(session: ChatSession) = chatStore.upsert(session)

    suspend fun deleteSession(id: String) = chatStore.delete(id)

    private companion object {
        const val MAX_MESSAGES = 24
        const val MAX_MESSAGE_CHARS = 6000
        const val MAX_MEMORIES = 8
        const val MAX_TERMS = 15

        val KEY_PATTERN = Regex("""sk-or-v1-[A-Za-z0-9]{20,}""")
        val STOP_WORDS = setOf(
            "para", "esta", "este", "esto", "este", "como", "cuando", "donde", "porque",
            "sobre", "con", "del", "los", "las", "una", "unos", "unas", "que", "cual",
            "cuales", "muy", "mas", "tambien", "puedes", "quiero", "necesito", "mira"
        )
    }
}