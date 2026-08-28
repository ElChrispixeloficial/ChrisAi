package com.chrispixel.chrisai.data.remote

import com.chrispixel.chrisai.BuildConfig
import com.chrispixel.chrisai.data.model.AiModel
import com.chrispixel.chrisai.nativebridge.NativeBridge
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
 * Outcome of a streamed completion, including v0.5 metrics:
 * [latencyMs] (time to first token) and [totalMs], plus token usage when the
 * provider reports it.
 */
data class StreamChatResult(
    val text: String,
    val latencyMs: Long?,
    val totalMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?
)

/**
 * Thin HTTP client for the OpenRouter API.
 *
 * Uses OkHttp with SSE streaming for chat completions (see [streamChat]).
 */
class OpenRouterApi(
    private val baseUrl: String = BuildConfig.OPENROUTER_BASE_URL
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val callMutex = Mutex()
    private var activeCall: Call? = null

    /** Interrupts the in-flight streaming request (used to stop generation). */
    suspend fun cancelActiveCall() {
        callMutex.withLock { activeCall?.cancel() }
    }

    /**
     * Streams a chat completion. Each content delta is delivered via [onDelta].
     * Measures time-to-first-token and total time; requests token usage.
     */
    suspend fun streamChat(
        messages: List<Map<String, String>>,
        model: String,
        apiKey: String,
        temperature: Double?,
        onDelta: (String) -> Unit
    ): StreamChatResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw OpenRouterException.NoApiKey()

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray(messages.map { JSONObject(it) }))
            put("stream", true)
            temperature?.let { put("temperature", it) }
            put("stream_options", JSONObject().put("include_usage", true))
        }.toString()

        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("chat/completions").build())
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Title", "ChrisAI")
            .post(payload.toRequestBody(jsonMediaType))
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
                throw OpenRouterException.InvalidResponse("Respuesta vacía del servidor")
            }
            val body = response.body!!

            if (!response.isSuccessful) {
                val errorBody = body.string()
                body.close()
                response.close()
                throw toOpenRouterException(response.code, errorBody)
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
                    parseUsage(data)?.let { (prompt, completion) ->
                        promptTokens = prompt
                        completionTokens = completion
                    }
                    val delta = parseDelta(data)
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
            StreamChatResult(
                text = sb.toString(),
                latencyMs = firstDeltaAt?.let { it - startedAt },
                totalMs = endedAt - startedAt,
                promptTokens = promptTokens,
                completionTokens = completionTokens
            )
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw when (e) {
                is InterruptedIOException -> OpenRouterException.Timeout()
                else -> OpenRouterException.Network(e.message ?: "Error de red", e)
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } finally {
            callMutex.withLock { if (activeCall === call) activeCall = null }
        }
    }

    /** Lists the available models from OpenRouter. */
    suspend fun listModels(apiKey: String): List<AiModel> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw OpenRouterException.NoApiKey()

        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("models").build())
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("X-Title", "ChrisAI")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw toOpenRouterException(response.code, body)
            try {
                val arr = JSONObject(body).getJSONArray("data")
                val list = ArrayList<AiModel>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pricing = o.optJSONObject("pricing")
                    list.add(
                        AiModel(
                            id = o.getString("id"),
                            name = o.optString("name").ifBlank { o.getString("id") },
                            contextLength = o.optLong("context_length", 0),
                            promptPrice = pricing?.optString("prompt").orEmpty()
                        )
                    )
                }
                list.sortedBy { it.id }
            } catch (e: Exception) {
                throw OpenRouterException.InvalidResponse("No se pudo interpretar la lista de modelos")
            }
        }
    }

    private fun parseDelta(data: String): String? {
        // Fast native path (C++): avoids allocating a full JSONObject per token.
        val native = NativeBridge.extractContentDelta(data)
        if (native != null) return native
        return try {
            JSONObject(data)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optString("content")
        } catch (_: Exception) {
            null
        }
    }

    private fun parseUsage(data: String): Pair<Int?, Int?>? {
        if (!data.contains("\"usage\"")) return null
        return try {
            val usage = JSONObject(data).optJSONObject("usage") ?: return null
            val prompt = usage.optInt("prompt_tokens", -1).takeIf { it >= 0 }
            val completion = usage.optInt("completion_tokens", -1).takeIf { it >= 0 }
            if (prompt == null && completion == null) null else (prompt to completion)
        } catch (_: Exception) {
            null
        }
    }

    private fun toOpenRouterException(code: Int, body: String): OpenRouterException {
        val message = try {
            JSONObject(body)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code"
        }
        return when (code) {
            429 -> OpenRouterException.RateLimited("Límite de peticiones alcanzado (429): $message")
            401, 403 -> OpenRouterException.Http(code, "API key inválida o sin permisos ($code): $message")
            in 400..499 -> OpenRouterException.Http(code, "Error en la petición ($code): $message")
            in 500..599 -> OpenRouterException.Http(code, "El servidor de OpenRouter falló ($code): $message")
            else -> OpenRouterException.Http(code, "HTTP $code: $message")
        }
    }
}