package com.chrispixel.chrisai.data.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.7 ChrisTools: structured, safe tool calling.
 *
 * The model never executes arbitrary code: it produces a structured
 * [ToolCallBlock] (envelope) that the app parses, validates against the
 * [ToolRegistry] and executes with controlled parameters.
 */

enum class ToolResultStatus {
    SUCCESS,
    RUNNING,                // UI-only transient phase during execution
    FAILED,
    NOT_FOUND,
    NOT_SUPPORTED,
    REQUIRES_CONFIRMATION,
    REQUIRES_SHIZUKU,
    NO_COMPATIBLE_APP,
    PERMISSION_DENIED
}

enum class ToolRiskLevel { SAFE, CONFIRMATION, RESTRICTED }

data class ToolParam(
    val name: String,
    val type: String,           // "string" | "integer" | "boolean"
    val description: String,
    val required: Boolean = false
)

data class ToolResult(
    val status: ToolResultStatus,
    val toolId: String,
    val message: String,
    val data: Map<String, String> = emptyMap(),
    val needsConfirmation: Boolean = false,
    val confirmationToken: String? = null
)

/** Structured call emitted by the model inside the [TOOLS] envelope. */
data class ToolCall(
    val id: String,
    val arguments: Map<String, String>
)

data class ToolCallBlock(
    val preamble: String,
    val calls: List<ToolCall>
)

/** A structured call to a concrete [Tool]. */
interface Tool {
    val id: String
    val name: String
    val description: String
    val parameters: List<ToolParam>
    val permissions: List<String>
    val risk: ToolRiskLevel
    val requiresConfirmation: Boolean
    val requiresShizuku: Boolean

    suspend fun execute(args: Map<String, String>): ToolResult
}

/**
 * Parses the envelope the model emits for tool usage.
 *
 * Format (the block is always the final part of the assistant text; the
 * visible text is everything before it):
 *
 *     [TOOLS]
 *     {"calls":[{"id":"open_app","arguments":{"appName":"YouTube"}}]}
 *     [/TOOLS]
 */
object ToolCallParser {

    private const val OPEN = "[TOOLS]"
    private const val CLOSE = "[/TOOLS]"

    /** Returns the block + calls, or null when the text carries no envelope. */
    fun parse(text: String): ToolCallBlock? {
        if (text.isBlank()) return null
        val openAt = text.indexOf(OPEN)
        if (openAt < 0) return null
        val closeAt = text.indexOf(CLOSE, openAt)
        if (closeAt < 0) return null

        val preamble = text.substring(0, openAt).trim()
        val inner = text.substring(openAt + OPEN.length, closeAt).trim()
        val calls = parseCalls(inner) ?: return null

        return ToolCallBlock(preamble = preamble, calls = calls)
    }

    /** Removes anything at/after the envelope so only visible text remains. */
    fun visibleText(text: String): String {
        val openAt = text.indexOf(OPEN)
        return if (openAt >= 0) text.substring(0, openAt).trim() else text.trim()
    }

    private fun parseCalls(inner: String): List<ToolCall>? {
        // Accept possibly-wrapped JSON.
        val jsonText = stripSurrounding(inner)
        return try {
            val root = JSONObject(jsonText)
            val callsArray = root.optJSONArray("calls") ?: return null
            val calls = ArrayList<ToolCall>(callsArray.length())
            for (i in 0 until callsArray.length()) {
                val call = callsArray.optJSONObject(i) ?: continue
                val id = call.optString("id").takeIf { it.isNotBlank() } ?: continue
                val args = HashMap<String, String>()
                call.optJSONObject("arguments")?.let { arguments ->
                    val keys = arguments.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = arguments.opt(key)
                        args[key] = when (value) {
                            null -> ""
                            is String -> value
                            else -> value.toString()
                        }
                    }
                }
                calls.add(ToolCall(id = id, arguments = args))
            }
            calls
        } catch (_: Exception) {
            null
        }
    }

    private fun stripSurrounding(text: String): String {
        var out = text.trim()
        out = out.removePrefix("```json").removePrefix("```").trim()
        while (out.endsWith("```")) out = out.dropLast(3).trim()
        return out
    }
}

/** Extensible registry of safe, structured tools. */
class ToolRegistry(tools: List<Tool>) {

    private val toolsById: Map<String, Tool> = tools.associateBy { it.id }

    /** Set by the app; false keeps the app fully functional without Shizuku. */
    var shizukuAvailable: Boolean = false

    fun all(): List<Tool> = toolsById.values.sortedBy { it.id }

    fun find(id: String): Tool? = toolsById[id]

    fun contains(id: String): Boolean = toolsById.containsKey(id)

    /**
     * Validates and executes a call. Confirmation and Shizuku requirements are
     * honored here so a model can never bypass them via prompt injection.
     */
    suspend fun execute(
        call: ToolCall,
        desiredToken: String? = null
    ): ToolResult {
        val tool = toolsById[call.id]
            ?: return ToolResult(ToolResultStatus.NOT_FOUND, call.id, "Herramienta desconocida: ${call.id}")

        if (tool.requiresShizuku && !shizukuAvailable) {
            return ToolResult(
                ToolResultStatus.REQUIRES_SHIZUKU,
                tool.id,
                "⚠️ Esta acción requiere Shizuku.",
                data = mapOf("requiresShizuku" to "true")
            )
        }

        if (tool.requiresConfirmation && desiredToken == null) {
            val token = confirmationToken(call)
            return ToolResult(
                ToolResultStatus.REQUIRES_CONFIRMATION,
                tool.id,
                "¿Confirmas esta acción: ${tool.name}?",
                needsConfirmation = true,
                confirmationToken = token
            )
        }

        val args = sanitize(call, tool)
        return tool.execute(args)
    }

    /** Confirmation tokens are derived from the call itself (deterministic). */
    fun confirmationToken(call: ToolCall): String =
        "${call.id}:${call.arguments.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }}"

    /** Extracts and coerces only the declared parameters. */
    private fun sanitize(call: ToolCall, tool: Tool): Map<String, String> {
        val declared = tool.parameters.associateBy { it.name }
        val out = HashMap<String, String>()
        for ((key, value) in call.arguments) {
            val param = declared[key] ?: continue
            when (param.type) {
                "integer" -> value.trim().toIntOrNull()?.let { out[key] = it.toString() }
                "boolean" -> when (value.trim().lowercase()) {
                    "true", "si", "sí", "1" -> out[key] = "true"
                    "false", "no", "0" -> out[key] = "false"
                }
                else -> out[key] = value.trim()
            }
        }
        return out
    }

    /** Spanish prompt text describing all tools for the model. */
    fun describe(): String = buildString {
        append("[HERRAMIENTAS DISPONIBLES]\n")
        append(
            "Puedes usar herramientas reales de Android para cumplir peticiones. " +
                "Solo se ejecutan acciones SEGURAS y estructuradas.\n"
        )
        toolsById.values.sortedBy { it.id }.forEach { tool ->
            append("\n- ").append(tool.id).append(": ").append(tool.name).append(".\n")
            append("  ").append(tool.description).append('\n')
            if (tool.parameters.isNotEmpty()) {
                append("  Parámetros: ")
                append(
                    tool.parameters.joinToString(", ") {
                        "${it.name} (${it.type})${if (it.required) " *obligatorio*" else ""} — ${it.description}"
                    }
                )
                append('\n')
            }
            if (tool.risk != ToolRiskLevel.SAFE) {
                append("  Nivel de riesgo: ").append(tool.risk.name).append('\n')
            }
            if (tool.requiresConfirmation) {
                append("  Requiere confirmación del usuario.\n")
            }
            if (tool.requiresShizuku) {
                append("  Requiere Shizuku.\n")
            }
        }
        append(
            "\n\nCómo llamar a una herramienta:\n" +
                "- Cuando el usuario pida una acción que puedes hacer, escribe al FINAL " +
                "de tu respuesta un bloque exacto:\n" +
                "[TOOLS]\n" +
                "{\"calls\":[{\"id\":\"open_app\",\"arguments\":{\"appName\":\"YouTube\"}}]}\n" +
                "[/TOOLS]\n" +
                "- Puedes encadenar varias llamadas en el mismo bloque.\n" +
                "- Ese bloque NO debe aparecer en el texto visible: la app lo procesa.\n" +
                "- El bloque debe estar siempre al final; el texto anterior es lo que verá el usuario.\n" +
                "- Nunca inventes resultados: espera a recibirlos y responde según el resultado real.\n" +
                "- No invoques ninguna herramienta para pedir hora, batería u otra consulta salvo que el usuario la pida."
        )
    }
}

/** Aggregated report of an executed block, for the assistant's second pass. */
data class ToolExecutionReport(
    val calls: List<ToolCall>,
    val results: List<ToolResult>
) {
    /** Textual summary fed back to the model in round 2. */
    fun summaryForModel(): String = buildString {
        results.forEach { result ->
            append("- ").append(result.toolId).append(": ")
            append(result.status.name).append(" — ").append(result.message)
            if (result.data.isNotEmpty()) {
                append(" | ")
                append(result.data.map { "${it.key}=${it.value}" }.joinToString(", "))
            }
            append('\n')
        }
    }

    val succeededCount: Int get() = results.count { it.status == ToolResultStatus.SUCCESS }
    val failedCount: Int get() = results.count {
        it.status != ToolResultStatus.SUCCESS &&
            it.status != ToolResultStatus.RUNNING
    }
}