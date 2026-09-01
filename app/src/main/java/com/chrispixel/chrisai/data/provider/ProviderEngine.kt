package com.chrispixel.chrisai.data.provider

import com.chrispixel.chrisai.data.vision.VisionSupport
import kotlinx.coroutines.delay

/** Classifies a model id for vision without any network call. */
fun interface VisionClassifier {
    fun support(model: String): VisionSupport
}

/**
 * Routes chat requests to the best provider.
 *
 * Rules (v0.9 provider engine, v1.0 bounded backoff):
 * - OpenRouter is always the primary provider.
 * - A task that needs vision AND whose model cannot see goes straight to the
 *   fallback (Gemini) when it declares the VISION capability.
 * - Recoverable errors (429/timeout/5xx/network) on the primary trigger a
 *   bounded retry of the SAME provider with exponential backoff
 *   (maxPrimaryRetries, never indefinite); if it still fails, exactly ONE
 *   attempt on the fallback. Fatal errors (401/403/other permanent 4xx) never
 *   retry or fall back, so a bad key can't cause an infinite loop.
 * - Provider keys are never rotated.
 */
class ProviderEngine(
    private val primary: AiProvider,
    private val fallback: AiProvider?,
    private val fallbackKey: String,
    private val visionClassifier: VisionClassifier,
    // v1.0: retries of the same provider before giving up (bounded backoff).
    private val maxPrimaryRetries: Int = 2,
    private val backoffBaseMs: Long = 500L,
    private val backoffMaxMs: Long = 4000L
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
            primaryWithBackoff(request, onDelta)
        } catch (e: ProviderCallException) {
            if (e.kind != ProviderErrorType.RETRYABLE) throw e
            if (fallback == null || fallbackKey.isBlank()) throw e
            // Fallback must satisfy the task's capabilities (e.g. vision).
            if (needsVision && AiCapability.VISION !in fallback.capabilities()) throw e
            fallback.stream(request, onDelta)
        }
    }

    /**
     * Runs the primary with a bounded number of retries for recoverable errors
     * and exponential backoff. Fatal errors and non-retryable failures surface
     * immediately (the caller decides about fallback).
     */
    private suspend fun primaryWithBackoff(
        request: ProviderRequest,
        onDelta: (String) -> Unit
    ): ProviderReply {
        var attempt = 0
        while (true) {
            try {
                return primary.stream(request, onDelta)
            } catch (e: ProviderCallException) {
                if (attempt >= maxPrimaryRetries || e.kind != ProviderErrorType.RETRYABLE) throw e
                attempt++
                delay(backoffMs(attempt))
            }
        }
    }

    private fun backoffMs(attempt: Int): Long =
        minOf(backoffBaseMs * (1L shl (attempt - 1)), backoffMaxMs)

    companion object {
        /** Bounded retries of the primary before a single fallback attempt. */
        const val DEFAULT_MAX_PRIMARY_RETRIES = 2
    }

    /** Interrupts whichever provider is streaming right now. */
    suspend fun cancelActiveCall() {
        primary.cancel()
        fallback?.cancel()
    }

    private fun canSee(model: String): Boolean =
        visionClassifier.support(model) != VisionSupport.NOT_SUPPORTED

    /**
     * v1.1: generates an image from [prompt]. Uses the primary provider when it
     * advertises IMAGE_GENERATION; on a retryable error it falls back to the
     * fallback provider when that one can generate too. [model] is the image
     * model id and [primaryKey]/[fallbackKeyOverride] are the resolved keys the
     * providers need (they are not resolved internally for generation).
     */
    suspend fun generateImage(
        model: String,
        prompt: String,
        primaryKey: String,
        fallbackKeyOverride: String? = null
    ): ByteArray? {
        val primaryGenerates = AiCapability.IMAGE_GENERATION in primary.capabilities()
        val fallbackGenerates = fallback != null &&
            AiCapability.IMAGE_GENERATION in fallback.capabilities()

        if (primaryGenerates) {
            try {
                return primary.generateImage(model, prompt, primaryKey)
            } catch (e: ProviderCallException) {
                if (e.kind != ProviderErrorType.RETRYABLE || !fallbackGenerates) throw e
            } catch (_: Throwable) {
                if (!fallbackGenerates) return null
            }
        }
        if (fallbackGenerates) {
            val key = fallbackKeyOverride?.takeIf { it.isNotBlank() } ?: fallbackKey
            if (key.isBlank()) throw ProviderCallException(
                ProviderErrorType.FATAL, 0, "No hay una clave configurada para generar imágenes."
            )
            return fallback?.generateImage(model, prompt, key)
        }
        throw ProviderCallException(
            ProviderErrorType.FATAL, 0, "Ningún proveedor configurado puede generar imágenes."
        )
    }
}