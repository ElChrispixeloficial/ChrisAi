package com.chrispixel.chrisai.data.actions

/**
 * v0.9 Fast Actions: deterministic, safe, local intent resolution.
 *
 * Simple commands ("Abre YouTube", "Pon una alarma") are resolved WITHOUT
 * sending everything to the model: lower latency, zero quota cost. When
 * ambiguous (multiple apps, unclear meaning), the caller falls back to the
 * model.
 */
sealed class FastAction {
    data class OpenApp(val query: String) : FastAction()
    data class SearchInApp(val appLabel: String?, val searchQuery: String) : FastAction()
    object OpenSettings : FastAction()
    data class SetAlarm(val hour: Int, val minute: Int) : FastAction()
    data class SetTimer(val minutes: Int) : FastAction()
    object WhatTime : FastAction()
    object Battery : FastAction()
    object DeviceInfo : FastAction()
    data class EndCall(val userText: String) : FastAction()
    object ExplainScreen : FastAction()
    data class AskContext(val reference: String) : FastAction()
}

sealed class FastActionParse {
    data class Matched(val action: FastAction) : FastActionParse()

    /** Caller should keep walking the pipeline (memory intent, then model). */
    object NotFastAction : FastActionParse()

    /** Looks like a fast action but cannot be resolved safely → ask the model. */
    object Ambiguous : FastActionParse()
}

object FastActions {

    /** Patterns considered "safe local" triggers. */
    private val endCallTriggers = listOf(
        "cuelga", "colgar", "cuelga la llamada", "cierra la llamada",
        "termina la llamada", "terminar la llamada", "finaliza la llamada",
        "cortar la llamada", "corta la llamada", "fin de llamada"
    )

    /** Parses [text] into a deterministic action, or asks to defer to the model. */
    fun parse(text: String): FastActionParse {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        if (endCallTriggers.any { lower.startsWith(it) || lower.contains(it) }) {
            // Inside a call this is handled by the VM; parsing stays pure.
            return FastActionParse.Matched(FastAction.EndCall(trimmed))
        }

        // Context references: "¿y cuál era el segundo?" after compound actions.
        contextReference(lower)?.let {
            return FastActionParse.Matched(it)
        }

        if (lower.contains("qué hora") || lower == "hora" || lower.contains("dime la hora")) {
            return FastActionParse.Matched(FastAction.WhatTime)
        }
        if (lower.contains("batería") || lower.contains("bateria") || lower.contains("carga")) {
            if (lower.contains("app") || lower.contains("aplicación")) {
                return FastActionParse.Ambiguous
            }
            return FastActionParse.Matched(FastAction.Battery)
        }
        if (lower.contains("dispositivo") || lower.contains("modelo del") || lower.contains("móvil tengo")) {
            return FastActionParse.Matched(FastAction.DeviceInfo)
        }

        parseAlarm(lower)?.let { return FastActionParse.Matched(it) }
        parseTimer(lower)?.let { return FastActionParse.Matched(it) }

        if (lower.contains("explicame esto") || lower.contains("explícame esto")) {
            return FastActionParse.Matched(FastAction.ExplainScreen)
        }

        // Compound search ("abre X y busca Y", "busca Y en X") before open so the
        // open parser never swallows the search clause.
        parseSearchInApp(lower)?.let { return it }

        val open = parseOpenCommand(lower, trimmed)
        if (open != null) return open

        return FastActionParse.NotFastAction
    }

    private fun contextReference(lower: String): FastAction? {
        val ordinal = when {
            lower.contains("el segundo") || lower.contains("la segunda") -> "2"
            lower.contains("el tercero") -> "3"
            lower.contains("el cuarto") -> "4"
            lower.contains("el primero") || lower.contains("la primera") -> "1"
            lower.contains("el último") || lower.contains("el ultimo") -> "ultimo"
            else -> return null
        }
        // Only treat it as a cross-action reference when the user asks "which one".
        val asks = lower.contains("cuál") || lower.contains("cual") ||
            lower.contains("era") || lower.contains("fue") || lower.contains("cuál era")
        if (!asks) return null
        return FastAction.AskContext(ordinal)
    }

    private fun parseOpenCommand(lower: String, original: String): FastActionParse? {
        // If the target trails into another clause ("abre X y después busca Y",
        // "abre X y dime qué viste") leave it to the planner / model.
        val compoundTail = listOf(
            " y busca", " y después", " y luego", " y dime", " y cuéntame",
            " y cuenta", " y además", " y pon", " y comparte"
        ).any { lower.contains(it) }
        if (compoundTail) return null
        val match = Regex("""(?:abre|abrir|ábreme|abreme|pon|inicia|iniciar|lanza)\s+(?:la\s+)?(?:app\s+)?(.{1,60})""")
            .find(lower)
            ?: return null
        val target = match.groupValues[1].trim().trimEnd('?', '.', '!')
        if (target.isBlank() || target.length < 2) return FastActionParse.Ambiguous
        // skip noisy verbs
        val stripped = target
            .replace(Regex("""(?:por favor|please)\s*$"""), "")
            .trim()
        if (stripped.isBlank()) return FastActionParse.Ambiguous
        if (stripped == "ajustes" || stripped.contains("ajustes del teléfono")) {
            return FastActionParse.Matched(FastAction.OpenSettings)
        }
        return FastActionParse.Matched(FastAction.OpenApp(stripped))
    }

    private fun parseSearchInApp(lower: String): FastActionParse? {
        // "abre YouTube (y, y después) busca Minecraft" / "busca X en Y" / "busca X"
        val yBuscas = Regex("""^(?:abre|abrir|ábreme|abreme)\s+(.{1,40}?)\s+(?:y\s+)?(?:después\s+)?busca\s+(.+)$""")
            .find(lower)
        if (yBuscas != null) {
            return FastActionParse.Matched(
                FastAction.SearchInApp(
                    appLabel = yBuscas.groupValues[1].trim(),
                    searchQuery = yBuscas.groupValues[2].trim().trimEnd('?', '.', '!')
                )
            )
        }
        val enBusca = Regex("""busca\s+(.+?)\s+(?:en|dentro de)\s+(.+)$""").find(lower)
        if (enBusca != null) {
            return FastActionParse.Matched(
                FastAction.SearchInApp(
                    appLabel = enBusca.groupValues[2].trim(),
                    searchQuery = enBusca.groupValues[1].trim().trimEnd('?', '.', '!')
                )
            )
        }
        val soloBusca = Regex("""busca\s+(.+)$""").find(lower)
        if (soloBusca != null) {
            return FastActionParse.Matched(
                FastAction.SearchInApp(
                    appLabel = null,
                    searchQuery = soloBusca.groupValues[1].trim().trimEnd('?', '.', '!')
                )
            )
        }
        return null
    }

    private fun parseAlarm(lower: String): FastAction? {
        if (!lower.contains("alarma")) return null
        val leading = !lower.contains("cancel") && !lower.contains("quita") && !lower.contains("elimina")
        if (!leading) return null
        val match = Regex("""(?:a\s+)?(\d{1,2})[':]?(\d{2})?\s*(?:a\.m\.|p\.m\.)?""").find(lower) ?: return null
        var hour = match.groupValues[1].toInt()
        if (lower.contains("p.m.") || lower.contains("pm") && hour < 12) hour += 12
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return FastAction.SetAlarm(hour, minute)
    }

    private fun parseTimer(lower: String): FastAction? {
        if (!lower.contains("temporizador") && !lower.contains("timer") && !lower.contains("minutos")) return null
        if (lower.contains("cancel") || lower.contains("quita") || lower.contains("elimina")) return null
        val match = Regex("""(\d{1,3})\s*(?:minutos|min|horas?)?""").find(lower) ?: return null
        val value = match.groupValues[1].toInt().coerceAtMost(720)
        if (lower.contains("hora") && !lower.contains("minutos")) {
            return FastAction.SetTimer(value * 60)
        }
        if (value in 1..720) return FastAction.SetTimer(value)
        return null
    }
}

/** Short, honest summary used for the cross-action context memory. */
fun FastAction.summaryLabel(): String = when (this) {
    is FastAction.OpenApp -> "Abrir $query"
    is FastAction.SearchInApp -> "Buscar «${searchQuery}»${if (appLabel != null) " en $appLabel" else ""}"
    FastAction.OpenSettings -> "Abrir ajustes del sistema"
    is FastAction.SetAlarm -> "Alarma %02d:%02d".format(hour, minute)
    is FastAction.SetTimer -> "Temporizador $minutes min"
    FastAction.WhatTime -> "Consultar la hora"
    FastAction.Battery -> "Consultar la batería"
    FastAction.DeviceInfo -> "Información del dispositivo"
    is FastAction.EndCall -> "Colgar la llamada"
    FastAction.ExplainScreen -> "Explicar la pantalla"
    is FastAction.AskContext -> "Recordar paso «$reference»"
}