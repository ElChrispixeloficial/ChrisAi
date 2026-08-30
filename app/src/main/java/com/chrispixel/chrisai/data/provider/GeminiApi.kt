package com.chrispixel.chrisai.data.provider

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTPS client for the Gemini API (used as the fallback provider in v0.9).
 *
 * Only TEXT/STREAMING/VISION are supported here: tool calls stay on OpenRouter
 * (the primary provider). Requests use the OpenAI chat shape and are converted
 * to Gemini's contents format by [GeminiPayload].
 */
class GeminiApi(
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val callMutex = Mutex()
    private var activeCall: Call? = null

    suspend fun cancelActiveCall() {
        callMutex.withLock { activeCall?.cancel() }
    }

    suspend fun streamChat(
        messages: List<Map<String, Any>>,
        model: String,
        apiKey: String,
        temperature: Double?,
        onDelta: (String) -> Unit
    ): ProviderReply = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw ProviderCallException(
                ProviderErrorType.FATAL, 0,
                "No hay una API key de Gemini configurada en esta build."
            )
        }

        val payload = GeminiPayload.build(geminiModel(model), messages, temperature)
        val request = Request.Builder()
            .url(
                baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments("models/${geminiModel(model)}:streamGenerateContent")
                    .addQueryParameter("alt", "sse")
                    .addQueryParameter("key", apiKey)
                    .build()
            )
            .addHeader("X-Goog-Api-Key", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Title", "ChrisAI")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val call = client.newCall(request)
        callMutex.withLock { activeCall = call }

        val startedAt = System.currentTimeMillis()
        var firstDeltaAt: Long? = null
        var promptTokens: Int? = null
        var completionTokens: Int? = null

        try {
            val response = call.execute()
            if (response.body == null) {
                response.close()
                throw ProviderCallException(
                    ProviderErrorType.RETRYABLE, 0, "Respuesta vacía de Gemini."
                )
            }
            val body = response.body!!

            if (!response.isSuccessful) {
                val errorBody = body.string()
                body.close()
                response.close()
                throw toProviderException(response.code, errorBody)
            }

            val source = body.source()
            val sb = StringBuilder()
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    GeminiPayload.parseUsage(data)?.let { (prompt, completion) ->
                        promptTokens = prompt
                        completionTokens = completion
                    }
                    val delta = GeminiPayload.parseDelta(data)
                    if (delta != null && delta.isNotEmpty()) {
                        if (firstDeltaAt == null) firstDeltaAt = System.currentTimeMillis()
                        sb.append(delta)
                        onDelta(delta)
                    }
                }
            } finally {
                response.close()
            }

            val endedAt = System.currentTimeMillis()
            ProviderReply(
                text = sb.toString(),
                latencyMs = firstDeltaAt?.let { it - startedAt },
                totalMs = endedAt - startedAt,
                promptTokens = promptTokens,
                completionTokens = completionTokens
            )
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw when (e) {
                is InterruptedIOException -> ProviderCallException(
                    ProviderErrorType.RETRYABLE, 0, "Gemini tardó demasiado (timeout).", e
                )
                else -> ProviderCallException(
                    ProviderErrorType.RETRYABLE, 0,
                    "Error de red con Gemini: ${e.message ?: "desconocido"}", e
                )
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } finally {
            callMutex.withLock { if (activeCall === call) activeCall = null }
        }
    }

    /** Normalizes any model id to something Gemini understands (bare ids). */
    private fun geminiModel(model: String): String = when {
        model.contains("/") -> model.substringAfterLast("/")
        else -> model
    }

    private fun toProviderException(code: Int, errorBody: String): ProviderCallException {
        val message = try {
            JSONObject(errorBody)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code"
        }
        val kind = when {
            code == 429 || code >= 500 || code == 408 -> ProviderErrorType.RETRYABLE
            code == 401 || code == 403 -> ProviderErrorType.FATAL
            else -> ProviderErrorType.FATAL
        }
        val label = if (kind == ProviderErrorType.RETRYABLE) "recuperable" else "permanente"
        return ProviderCallException(kind, code, "Gemini ($label): $message")
    }
}

/**
 * Pure conversion from OpenAI-format messages to Gemini's contents shape.
 * Kept dependency-light so JVM tests can exercise it directly.
 */
object GeminiPayload {

    /** Builds the generateContent request body for Gemini. */
    fun build(model: String, messages: List<Map<String, Any>>, temperature: Double?): JSONObject {
        val systemParts = JSONArray()
        val contents = JSONArray()

        messages.forEach { raw ->
            val role = raw["role"] as? String ?: "user"
            val content = raw["content"]

            if (role == "system") {
                appendTextPart(systemParts, stringContent(content))
                return@forEach
            }

            val geminiRole = if (role == "assistant") "model" else "user"
            val parts = JSONArray()
            when (content) {
                is String -> appendTextPart(parts, content)
                is JSONArray -> {
                    for (i in 0 until content.length()) {
                        val rawPart = content.get(i)
                        when (rawPart) {
                            is JSONObject -> {
                                when (rawPart.optString("type")) {
                                    "text" -> rawPart.optString("text").takeIf { it.isNotBlank() }
                                        ?.let { appendTextPart(parts, it) }
                                    "image_url" -> {
                                        val url = rawPart.optJSONObject("image_url")?.optString("url").orEmpty()
                                        inlineData(url)?.let { parts.put(it) }
                                    }
                                    else -> Unit
                                }
                            }
                            else -> appendTextPart(parts, rawPart.toString())
                        }
                    }
                }
                else -> appendTextPart(parts, content.toString())
            }
            contents.put(JSONObject().put("role", geminiRole).put("parts", parts))
        }

        val body = JSONObject()
        if (systemParts.length() > 0) {
            body.put("systemInstruction", JSONObject().put("parts", systemParts))
        }
        body.put("contents", contents)
        val generation = JSONObject()
        temperature?.let { generation.put("temperature", it) }
        generation.put("maxOutputTokens", 2048)
        generation.put("candidateCount", 1)
        body.put("generationConfig", generation)
        return body
    }

    private fun stringContent(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> content.optJSONObject(0)?.optString("text").orEmpty()
        else -> content?.toString().orEmpty()
    }

    private fun appendTextPart(parts: JSONArray, text: String) {
        if (text.isNotBlank()) parts.put(JSONObject().put("text", text))
    }

    /**
     * Converts an OpenAI image_url data-URI into Gemini inlineData parts, or
     * null when the URL is not a data URI we can read locally.
     */
    internal fun inlineData(dataUri: String): JSONObject? {
        if (!dataUri.startsWith("data:")) return null
        val comma = dataUri.indexOf(',')
        if (comma < 0) return null
        val meta = dataUri.substring(5, comma)
        val b64 = dataUri.substring(comma + 1)
        if (b64.isBlank()) return null
        val mime = meta.substringBefore(";").takeIf { it.startsWith("image/") } ?: "image/jpeg"
        return JSONObject()
            .put("inlineData", JSONObject().put("mimeType", mime).put("data", b64))
    }

    /** Extracts the concatenated text from a Gemini SSE content chunk. */
    fun parseDelta(data: String): String? {
        return try {
            val candidates = JSONObject(data).optJSONArray("candidates") ?: return null
            val parts = candidates
                .optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i)
                val text = part?.optString("text")
                if (!text.isNullOrEmpty()) sb.append(text)
            }
            sb.toString().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Extracts usage metadata from a Gemini SSE chunk. */
    fun parseUsage(data: String): Pair<Int?, Int?>? {
        if (!data.contains("usageMetadata")) return null
        return try {
            val usage = JSONObject(data).optJSONObject("usageMetadata") ?: return null
            val prompt = usage.optInt("promptTokenCount", -1).takeIf { it >= 0 }
            val completion = usage.optInt("candidatesTokenCount", -1).takeIf { it >= 0 }
            if (prompt == null && completion == null) null else (prompt to completion)
        } catch (_: Exception) {
            null
        }
    }
}