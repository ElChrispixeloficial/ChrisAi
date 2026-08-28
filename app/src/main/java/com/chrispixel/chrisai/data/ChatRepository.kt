package com.chrispixel.chrisai.data

import com.chrispixel.chrisai.data.local.ChatStore
import com.chrispixel.chrisai.data.local.MemoryIntent
import com.chrispixel.chrisai.data.local.MemoryStore
import com.chrispixel.chrisai.data.model.AiModel
import com.chrispixel.chrisai.data.model.ChatRole
import com.chrispixel.chrisai.data.model.ChatSession
import com.chrispixel.chrisai.data.model.Memory
import com.chrispixel.chrisai.data.remote.OpenRouterApi

data class StreamReply(
    val text: String,
    val latencyMs: Long?,
    val totalMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?
)

/**
 * Orchestrates requests to OpenRouter: builds the payload
 * (persona + only the relevant memory context + a trimmed history) and streams
 * the assistant reply, measuring latency and tokens and handling the memory
 * tags the model may emit.
 */
class ChatRepository(
    private val api: OpenRouterApi,
    private val chatStore: ChatStore,
    private val settings: SettingsRepository,
    private val memory: MemoryStore
) {

    private val apiKey get() = settings.apiKey.value

    /** Streams a reply for [session]. Returns the cleaned text plus metrics. */
    suspend fun streamReply(
        session: ChatSession,
        onDelta: (String) -> Unit
    ): StreamReply {
        val latestUser = session.messages.lastOrNull { it.role == ChatRole.USER }?.content
        val relevantMemories = relevantMemories(latestUser)

        val payload = buildList {
            add(mapOf("role" to "system", "content" to Prompts.SYSTEM_PROMPT))
            add(mapOf("role" to "system", "content" to memory.asContext(relevantMemories)))
            session.messages
                .takeLast(MAX_MESSAGES)
                .filter { it.role != ChatRole.SYSTEM }
                .forEach { message ->
                    add(mapOf("role" to message.role.apiValue, "content" to redact(trim(message.content))))
                }
        }

        val result = api.streamChat(
            messages = payload,
            model = session.model.ifBlank { settings.model.value },
            apiKey = apiKey,
            temperature = settings.temperature.value,
            onDelta = onDelta
        )

        val tagged = MemoryIntent.parseTags(result.text)
        tagged.toSave.forEach { memory.add(it) }
        tagged.toForget.forEach { memory.removeContaining(it) }

        return StreamReply(
            text = tagged.cleaned,
            latencyMs = result.latencyMs,
            totalMs = result.totalMs,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens
        )
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