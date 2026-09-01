package com.chrispixel.chrisai.data.provider

import com.chrispixel.chrisai.data.remote.OpenRouterApi
import com.chrispixel.chrisai.data.remote.OpenRouterException

/**
 * OpenRouter as an [AiProvider]: primary provider with full capabilities
 * (text, streaming, vision, tools). Errors are translated into
 * [ProviderCallException] with a retry/fatal classification so the engine can
 * fall back to Gemini without ever rotating keys.
 *
 * The key is resolved lazily from [apiKeyProvider] because the active key can
 * be a user-provided runtime override (encrypted in settings).
 */
class OpenRouterProvider(
    private val api: OpenRouterApi,
    private val apiKeyProvider: () -> String
) : AiProvider {

    override val id: String = "openrouter"
    override val baseModel: String = "openrouter/free"

    override fun capabilities(): Set<AiCapability> = setOf(
        AiCapability.TEXT,
        AiCapability.STREAMING,
        AiCapability.VISION,
        AiCapability.TOOLS,
        AiCapability.IMAGE_GENERATION
    )

    override suspend fun generateImage(
        model: String,
        prompt: String,
        apiKey: String
    ): ByteArray? = try {
        val bytes = api.generateImage(model = model, prompt = prompt, apiKey = apiKey)
        bytes ?: throw ProviderCallException(
            ProviderErrorType.FATAL, 0, "El modelo devolvió una imagen vacía."
        )
    } catch (e: OpenRouterException) {
        throw e.toProviderException()
    }

    override suspend fun stream(
        request: ProviderRequest,
        onDelta: (String) -> Unit
    ): ProviderReply = try {
        val result = api.streamChat(
            messages = request.messages,
            model = request.model,
            apiKey = apiKeyProvider(),
            temperature = request.temperature,
            onDelta = onDelta
        )
        ProviderReply(
            text = result.text,
            latencyMs = result.latencyMs,
            totalMs = result.totalMs,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens
        )
    } catch (e: OpenRouterException) {
        throw e.toProviderException()
    }

    override suspend fun cancel() = api.cancelActiveCall()

    private fun OpenRouterException.toProviderException(): ProviderCallException = when (this) {
        is OpenRouterException.NoApiKey -> ProviderCallException(
            ProviderErrorType.FATAL, 0, message ?: "Sin API key de OpenRouter."
        )
        is OpenRouterException.InvalidResponse -> ProviderCallException(
            ProviderErrorType.FATAL, 0, message ?: "Respuesta inválida de OpenRouter."
        )
        is OpenRouterException.Unexpected -> ProviderCallException(
            ProviderErrorType.RETRYABLE, 0, message ?: "Error inesperado de OpenRouter.", cause
        )
        is OpenRouterException.Timeout -> ProviderCallException(
            ProviderErrorType.RETRYABLE, 408, message ?: "Timeout."
        )
        is OpenRouterException.Network -> ProviderCallException(
            ProviderErrorType.RETRYABLE, 0, message ?: "Error de red.", cause
        )
        is OpenRouterException.RateLimited -> ProviderCallException(
            ProviderErrorType.RETRYABLE, 429, message ?: "Rate limit."
        )
        is OpenRouterException.Http -> {
            val retryable = status == 429 || status >= 500 || status == 408
            ProviderCallException(
                if (retryable) ProviderErrorType.RETRYABLE else ProviderErrorType.FATAL,
                status,
                message ?: "HTTP $status"
            )
        }
    }
}