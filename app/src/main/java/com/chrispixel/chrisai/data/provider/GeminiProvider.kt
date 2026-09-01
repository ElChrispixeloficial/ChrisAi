package com.chrispixel.chrisai.data.provider

/**
 * Gemini as an [AiProvider]: the fallback used on recoverable errors (429,
 * timeouts, certain 5xx, network) or when the current model cannot see.
 *
 * Capabilities: TEXT, STREAMING, VISION (not TOOLS — function calling stays on
 * OpenRouter, the primary provider).
 */
class GeminiProvider(
    private val api: GeminiApi,
    private val apiKey: String
) : AiProvider {

    override val id: String = "gemini"
    override val baseModel: String = "gemini-2.0-flash"

    override fun capabilities(): Set<AiCapability> = setOf(
        AiCapability.TEXT,
        AiCapability.STREAMING,
        AiCapability.VISION
    )
    override suspend fun stream(
        request: ProviderRequest,
        onDelta: (String) -> Unit
    ): ProviderReply = api.streamChat(
        messages = request.messages,
        model = request.model,
        apiKey = apiKey,
        temperature = request.temperature,
        onDelta = onDelta
    )

    override suspend fun cancel() = api.cancelActiveCall()
}