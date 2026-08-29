package com.chrispixel.chrisai.data.vision

import org.json.JSONArray
import org.json.JSONObject

/** How the current model is rated for a vision request (v0.8). */
enum class VisionSupport {
    /** Known to accept image/message content parts. */
    LIKELY_SUPPORTED,

    /** Known text-only model (guard: fail fast with a clear message). */
    NOT_SUPPORTED,

    /** Unknown model — attempt and surface the provider error if it fails. */
    UNKNOWN
}

/**
 * Pure helpers for multimodal requests through the existing single-message
 * pipeline. Builds the OpenAI-compatible content parts (image as a data URI)
 * that OpenRouter forwards to vision-capable models, and classifies a model id
 * without querying anything.
 */
object VisionMessage {

    private const val MAX_IMAGE_BYTES = 3_500_000 // ~3.5MB avoids provider limits

    /**
     * Builds a user message for a vision-capable model.
     * [base64Image] must be the image bytes encoded in Base64 (no wrapping).
     * [prompt] is the textual instruction that accompanies the image.
     */
    fun buildUserMessage(base64Image: String, mimeType: String?, prompt: String): String {
        val mime = mimeType?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$base64Image")))
        return JSONObject().put("role", "user").put("content", content).toString()
    }

    /** Structural check: has a text part and an image_url part with a data: URI. */
    fun isValidVisionMessage(json: String): Boolean {
        return try {
            val content = JSONObject(json).optJSONArray("content") ?: return false
            var hasImage = false
            var hasText = false
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "image_url" -> {
                        val url = part.optJSONObject("image_url")?.optString("url").orEmpty()
                        if (url.startsWith("data:image/")) hasImage = true
                    }
                    "text" -> if (part.optString("text").isNotBlank()) hasText = true
                }
            }
            hasImage && hasText
        } catch (_: Exception) {
            false
        }
    }

    /** Guard: refuses oversized local images before the model call. */
    fun isReasonableImageSize(bytes: Int): Boolean = bytes in 1..MAX_IMAGE_BYTES

    /** Classifies a model id without querying (v0.8 "detect incompatibility"). */
    fun support(modelId: String): VisionSupport {
        val id = modelId.lowercase().trim()
        if (id.isBlank()) return VisionSupport.UNKNOWN
        if (KNOWN_TEXT_ONLY.any { it in id }) return VisionSupport.NOT_SUPPORTED
        if (KNOWN_VISION.any { it in id }) return VisionSupport.LIKELY_SUPPORTED
        if (OPENROUTER_FREE_VISION.any { it in id }) return VisionSupport.LIKELY_SUPPORTED
        return VisionSupport.UNKNOWN
    }

    /** Human-readable hint used in the UI when the model can't see. */
    fun unsupportedErrorMessage(modelId: String): String =
        "El modelo actual ($modelId) no admite imágenes. Elige uno con visión " +
            "(p. ej. google/gemini-2.0-flash, openai/gpt-4o-mini o meta-llama/llama-3.2-90b-vision) " +
            "desde Ajustes."

    private val KNOWN_VISION = listOf(
        "vision", "gemini", "gpt-4o", "gpt-4.1", "gpt-4.5", "gpt-5", "gpt-4-turbo",
        "llava", "moondream", "qwen2-vl", "qwen2.5-vl", "qwen3-vl", "qwen-vl",
        "internvl", "minicpm", "claude-3", "claude-4", "glm-4v", "olmocr", "pixtral",
        "fuyu", "idefics", "paligemma", "grok-2-vision", "gemma-3", "phi-3-vision",
        "step-1o", "seed1", "aion", "openai/gpt-5", "openai/gpt-4"
    )

    private val KNOWN_TEXT_ONLY = listOf(
        "gpt-3.5", "o3-mini", "o4-mini", "deepseek", "qwen-turbo", "qwen-plus",
        "grok-3", "grok-4", "openrouter/auto", "qwen2.5-coder"
    )

    /** OpenAI-compatible free models on OpenRouter known to handle images. */
    private val OPENROUTER_FREE_VISION = listOf(
        "google/gemini-2.0-flash", "google/gemini-2.5-flash", "qwen/qwen2.5-vl",
        "meta-llama/llama-3.2-11b-vision", "meta-llama/llama-3.2-90b-vision"
    )
}