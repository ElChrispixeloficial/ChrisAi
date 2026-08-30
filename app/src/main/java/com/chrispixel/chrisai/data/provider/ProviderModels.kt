package com.chrispixel.chrisai.data.provider

/**
 * v0.9 Provider Engine: a provider-neutral abstraction over the models.
 *
 * OpenRouter stays the primary provider; a configured fallback (Gemini) is used
 * ONLY for recoverable errors (429/timeout/5xx/network) or when the current
 * task needs a capability the primary model lacks (e.g. vision). Keys are never
 * rotated, and an invalid-key error (401/403) never triggers a fallback loop.
 */
enum class AiCapability {
    TEXT,
    STREAMING,
    VISION,
    TOOLS
}

/** Whether a provider error is safe to retry on a different provider. */
enum class ProviderErrorType {
    /** 429, timeouts, certain 5xx, transient network issues. */
    RETRYABLE,

    /** 401/403 (invalid key), malformed inputs, permanent 4xx. */
    FATAL
}

/** Structured error thrown by providers (keeps secrets out of logs). */
class ProviderCallException(
    val kind: ProviderErrorType,
    val status: Int,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** Streaming result, mirrors OpenRouter's metrics so the UI is untouched. */
data class ProviderReply(
    val text: String,
    val latencyMs: Long? = null,
    val totalMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null
)

/** Request handed to a provider. Messages use the OpenAI chat format. */
data class ProviderRequest(
    val messages: List<Map<String, Any>>,
    val model: String,
    val temperature: Double? = null
)

/**
 * Minimal streaming contract shared by every provider. Implementations must be
 * idempotent-safe: each call holds its own in-flight HTTP resource so the
 * engine can cancel without breaking future calls.
 */
interface AiProvider {
    val id: String
    val baseModel: String

    fun capabilities(): Set<AiCapability>

    suspend fun stream(
        request: ProviderRequest,
        onDelta: (String) -> Unit
    ): ProviderReply

    /** Interrupts the in-flight request, if any. */
    suspend fun cancel()
}