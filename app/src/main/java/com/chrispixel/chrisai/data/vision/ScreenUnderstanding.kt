package com.chrispixel.chrisai.data.vision

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.9 Screen Understanding (VisionEngine 2).
 *
 * Beyond "what object is this": the model is asked to describe the interface —
 * text, buttons, menus, dialogs, errors, titles, and approximate positions —
 * in a small, machine-friendly JSON block, which this parser turns into a
 * compact observation feeding the Context Engine. v0.9 is descriptive only;
 * executing UI actions will require a later opt-in Accessibility layer.
 */
enum class ScreenElementType {
    BUTTON, TEXT, TITLE, MENU, ICON, INPUT, ERROR, DIALOG, CARD, OTHER
}

data class ScreenElement(
    val type: ScreenElementType,
    val text: String,
    val position: String? = null
)

object ScreenUnderstanding {

    /** Prompt used for screen/camera captures (kept concise for low-token cost). */
    fun visionPrompt(extraHint: String = ""): String = buildString {
        append("Eres la vista de ChrisAI. Analiza la imagen como pantalla o escena real. ")
        append("Identifica: textos visibles, botones, títulos, menús, diálogos, errores, ")
        append("iconos y la posición aproximada de cada elemento (arriba/abajo/izquierda/derecha/centro). ")
        append("Si la imagen no es una pantalla, describe simplemente qué se ve de forma breve. ")
        append(extraHint)
        append("\nAdemás, termina tu respuesta con un bloque JSON exacto así, " +
            "después de tu descripción (un solo JSON, sin commentarios):\n")
        append("{\"elements\":[{\"type\":\"boton\",\"text\":\"...\",\"pos\":\"arriba-derecha\"}]}")
    }

    /** Parses the model's elements JSON block (lenient). */
    fun parseElements(json: String): List<ScreenElement> {
        val text = extractJson(json) ?: return emptyList()
        return try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("elements")
            if (arr == null) return emptyList()
            val out = ArrayList<ScreenElement>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    ScreenElement(
                        type = classifyType(o.optString("type")),
                        text = o.optString("text").take(160),
                        position = o.optString("pos").takeIf { it.isNotBlank() }
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Human summary of elements (what the model's vision replies help with). */
    fun summarize(elements: List<ScreenElement>): String {
        if (elements.isEmpty()) return ""
        val notable = elements.filter { it.type != ScreenElementType.ICON && it.text.isNotBlank() }
        val main = if (notable.isEmpty()) {
            elements.firstOrNull()?.let { it.text.ifBlank { it.type.name.lowercase() } } ?: ""
        } else {
            notable.take(3).joinToString(" · ") { el ->
                val where = el.position?.let { ", $it" } ?: ""
                "${el.type.label}: ${el.text}$where"
            }
        }
        return "Elementos(¿dónde tocar/buscar): $main".trim()
    }

    /** Converts raw vision text into a compact observation for the context. */
    fun describeScreen(rawVisionText: String): String {
        val elements = parseElements(rawVisionText)
        if (elements.isNotEmpty()) {
            val lines = elements.map { el ->
                val pos = el.position?.let { " [$it]" } ?: ""
                "- ${el.type.label}${if (el.text.isNotBlank()) ": ${el.text}" else ""}$pos"
            }
            return buildString {
                append("PANTALLA DETECTADA:\n")
                append(lines.take(12).joinToString("\n"))
            }.trim()
        }
        // No structured block: keep the model's raw description (bounded).
        return rawVisionText.trim().take(1500)
    }

    private fun classifyType(raw: String): ScreenElementType = when {
        raw.contains("boton") || raw.contains("button") -> ScreenElementType.BUTTON
        raw.contains("titulo") || raw.contains("title") || raw.contains("header") -> ScreenElementType.TITLE
        raw.contains("menu") -> ScreenElementType.MENU
        raw.contains("dialog") || raw.contains("popup") -> ScreenElementType.DIALOG
        raw.contains("error") || raw.contains("alerta") || raw.contains("warning") -> ScreenElementType.ERROR
        raw.contains("input") || raw.contains("campo") || raw.contains("textfield") -> ScreenElementType.INPUT
        raw.contains("icono") || raw.contains("icon") -> ScreenElementType.ICON
        raw.contains("card") || raw.contains("tarjeta") -> ScreenElementType.CARD
        raw.contains("texto") || raw.contains("text") -> ScreenElementType.TEXT
        else -> ScreenElementType.OTHER
    }

    private val ScreenElementType.label: String
        get() = when (this) {
            ScreenElementType.BUTTON -> "botón"
            ScreenElementType.TEXT -> "texto"
            ScreenElementType.TITLE -> "título"
            ScreenElementType.MENU -> "menú"
            ScreenElementType.ICON -> "icono"
            ScreenElementType.INPUT -> "campo de texto"
            ScreenElementType.ERROR -> "error"
            ScreenElementType.DIALOG -> "diálogo"
            ScreenElementType.CARD -> "tarjeta"
            ScreenElementType.OTHER -> "elemento"
        }

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        // Find the matching closing brace, tolerating nested braces.
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

/**
 * Policy for controlled/periodic captures (camera & screen sharing).
 * Captures are bounded (2..60 s) to protect data/quota, and both sources
 * require explicit activation — never silent.
 */
object ScreenCapturePolicy {

    data class Spec(
        val intervalSec: Int,
        val maxCaptureBytes: Int = 1_200_000,
        val jpegQuality: Int = 80
    ) {
        val boundedIntervalSec: Int = intervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
    }

    const val MIN_INTERVAL_SEC = 2
    const val MAX_INTERVAL_SEC = 60
    const val DEFAULT_INTERVAL_SEC = 5

    fun spec(intervalSec: Int): Spec = Spec(intervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC))

    fun shouldCapture(lastCaptureAtMillis: Long, intervalSec: Int, now: Long = System.currentTimeMillis()): Boolean {
        val bounded = intervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
        return now - lastCaptureAtMillis >= bounded * 1000L
    }
}