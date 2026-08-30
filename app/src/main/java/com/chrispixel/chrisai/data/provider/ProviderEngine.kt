package com.chrispixel.chrisai.data.provider

import com.chrispixel.chrisai.data.vision.VisionSupport

/** Classifies a model id for vision without any network call. */
fun interface VisionClassifier {
    fun support(model: String): VisionSupport
}

/**
 * Routes chat requests to the best provider.
 *
 * Rules (v0.9 provider engine):
 * - OpenRouter is always the primary provider.
 * - A task that needs vision AND whose model cannot see goes straight to the
 *   fallback (Gemini) when it declares the VISION capability.
 * - Recoverable errors (429/timeout/5xx/network) on the primary trigger exactly
 *   ONE attempt on the fallback. Fatal errors (401/403/other permanent 4xx)
 *   never fall back, so a bad key can't cause an infinite loop.
 * - Provider keys are never rotated.
 */
class ProviderEngine(
    private val primary: AiProvider,
    private val fallback: AiProvider?,
    private val fallbackKey: String,
    private val visionClassifier: VisionClassifier
) {

    val primaryId: String get() = primary.id

    val fallbackId: String? get() = fallback?.id

    /** True when the fallback is both configured and can see images. */
    val fallbackVisionCapable: Boolean
        get() = fallback != null &&
            fallbackKey.isNotBlank() &&
            AiCapability.VISION in fallback.capabilities()

    suspend fun streamChat(
        messages: List<Map<String, Any>>,
        model: String,
        temperature: Double?,
        onDelta: (String) -> Unit,
        needsVision: Boolean = false
    ): ProviderReply {
        val request = ProviderRequest(
            messages = messages,
            model = model,
            temperature = temperature
        )

        // Vision gap: model can't see but the fallback can -> use it directly.
        if (needsVision && !canSee(model) && fallbackVisionCapable) {
            return requireNotNull(fallback).stream(request, onDelta)
        }

        return try {
            primary.stream(request, onDelta)
        } catch (e: ProviderCallException) {
            if (e.kind != ProviderErrorType.RETRYABLE) throw e
            if (fallback == null || fallbackKey.isBlank()) throw e
            // Fallback must satisfy the task's capabilities (e.g. vision).
            if (needsVision && AiCapability.VISION !in fallback.capabilities()) throw e
            fallback.stream(request, onDelta)
        }
    }

    /** Interrupts whichever provider is streaming right now. */
    suspend fun cancelActiveCall() {
        primary.cancel()
        fallback?.cancel()
    }

    private fun canSee(model: String): Boolean =
        visionClassifier.support(model) != VisionSupport.NOT_SUPPORTED
}