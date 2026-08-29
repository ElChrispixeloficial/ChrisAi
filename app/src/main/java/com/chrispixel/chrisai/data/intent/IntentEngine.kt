package com.chrispixel.chrisai.data.intent

/**
 * Result of [IntentEngine.detect]. The engine decides WHAT the user wants; the
 * engines (ChatRepository, MemoryStore, ChrisTools, Vision…) then do the work.
 */
sealed interface Intent {
    /** Plain conversation: reply via ChatRepository as usual. */
    object Conversation : Intent

    /** "Recuerda…" / "Guarda esto…" / "Memoriza…" — payload is the raw utterance. */
    data class SaveMemory(val raw: String) : Intent

    /** "Olvida…" — subject to remove from permanent memory. */
    data class ForgetMemory(val subject: String) : Intent

    /** Explicit request to read the user's memories about [topic]. */
    data class RecallMemory(val topic: String?) : Intent

    /** "Abre <app>…" — ChrisTools open_app. */
    data class OpenApp(val appName: String) : Intent

    /** "Busca <query>…"/"Busca una app de…" — search installed apps. */
    data class SearchApps(val query: String) : Intent

    data class CreateTimer(val minutes: Int, val seconds: Int) : Intent

    data class CreateAlarm(val hours: Int, val minutes: Int, val label: String?) : Intent

    object CancelTimerOrAlarm : Intent

    /** A URL was pasted/told — ChrisTools open_url (the model can confirm). */
    data class OpenUrl(val url: String) : Intent

    /** "Mira esto" / "analiza la foto" — attach an image and ask the model. */
    object VisionNow : Intent

    /** "¿Qué estoy viendo? / analiza la pantalla" — explicit screen capture. */
    object VisionScreen : Intent

    /** "Guarda la conversación" — persist history (not memory). */
    object SaveConversation : Intent

    data class SetMode(val mode: SessionMode) : Intent

    /** "Resumen de la sesión" — summarize the current conversation. */
    object SessionSummary : Intent

    /** "Busca <q> en internet" — multimodal/web search. */
    data class SearchWeb(val query: String) : Intent

    /** Nothing actionable; fall back to conversation. */
    object None : Intent
}

/**
 * Pure rule-based intent detector (v0.8). Explicit commands win over
 * heuristics; anything else is [Intent.Conversation].
 */
object IntentEngine {

    fun detect(text: String): Intent {
        val t = text.trim()
        val lower = t.lowercase()

        SessionMode.fromText(t)?.let { return Intent.SetMode(it) }

        if (isVisionNow(lower)) return Intent.VisionNow
        if (isVisionScreen(lower)) return Intent.VisionScreen

        if (isMemorySave(lower)) return Intent.SaveMemory(t)
        Regex("^\\s*recuerda\\s+(.+)", RegexOption.IGNORE_CASE).find(t)?.let {
            return Intent.SaveMemory(t)
        }
        if (isMemoryForget(lower)) {
            Regex("^\\s*olvida\\s+(.+)", RegexOption.IGNORE_CASE).find(t)?.let { m ->
                return Intent.ForgetMemory(m.groupValues[1].cleanup())
            }
            return Intent.ForgetMemory("todo")
        }

        if (Regex("\\bguarda la conversaci[oó]n\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return Intent.SaveConversation
        }
        if (Regex("\\b(resumen de (esta |la |mi )?sesi[oó]n|resume la sesi[oó]n|qu[eé] hemos hablado)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return Intent.SessionSummary
        }
        if (Regex("\\b(qu[eé] recuerdas|recuerdas algo|qu[eé] sabes de m[ií]|recuerdas mi)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return Intent.RecallMemory(null)
        }

        // "busca X en internet" → web search (before app search).
        val webQuery = extractWebSearch(t, lower)
        if (webQuery != null) return Intent.SearchWeb(webQuery)

        decodeTimer(lower, t)?.let { return it }
        decodeAlarm(lower, t)?.let { return it }

        if (Regex("\\bcancela (el )?(temporizador|alarma|timer)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return Intent.CancelTimerOrAlarm
        }

        // Explicit URL anywhere in the utterance → open it (ChrisTools open_url).
        Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            return Intent.OpenUrl(m.value.trimEnd('.', ',', ';', ':', '»', ')', ']'))
        }

        Regex("^\\s*abre\\s+(.+)", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val app = m.groupValues[1].cleanupApp()
            if (app.isNotBlank()) return Intent.OpenApp(app)
        }
        Regex("\\b(abre|lanza|inicia) (la app( de)? )?([a-z0-9][a-z0-9 ._-]{1,40})\\b", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val app = m.groupValues[4]
            if (app.isNotBlank()) return Intent.OpenApp(app)
        }

        Regex("^\\s*busca\\s+(.+)", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val query = m.groupValues[1]
                .replace(Regex("\\s+(en la play store|en play store|en google play)\\s*$"), "")
                .cleanupApp()
            if (query.isNotBlank()) return Intent.SearchApps(query)
        }

        if (Regex("^\\s*https?://\\S+", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            return Intent.OpenUrl(Regex("""https?://\S+""").find(t)!!.value.trimEnd('.', ',', ';'))
        }

        return Intent.Conversation
    }

    private fun isVisionNow(lower: String): Boolean = Regex(
        "^[¿¡]*\\s*(mira esto|mira a ver esto)" + // short explicit action
            "|^[¿¡]*\\s*(mira|analiza|describe|a ver)\\s+(esta|la|mi)\\s+(imagen|foto|fotograf[íi]a|captura|apunte)" +
            "|^[¿¡]*\\s*(qu[eé] apare(ce|cen)( aqu[íi])?|qu[eé] se ve( aqu[íi])?|qu[eé] contiene)" +
            "|^[¿¡]*\\s*(qu[eé] es esto|qu[eé] dice aqu[íi])" +
            "|^[¿¡]*\\s*explica esta (imagen|foto)",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(lower)

    private fun isVisionScreen(lower: String): Boolean = Regex(
        "^[¿¡]*\\s*(qu[eé] estoy viendo|qu[eé] tengo que tocar|comparte (conmigo )?(la )?pantalla|captura (mi )?pantalla|expl[íi]came esta pantalla)" +
            "|^[¿¡]*\\s*analiza (mi |la )?pantalla",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(lower)

    private fun isMemorySave(lower: String): Boolean = Regex(
        "\\b(memoriza que |memoriza |guarda esto en tu memoria|guarda esto en la memoria|" +
            "guardame esto|guarda en tu memoria|recuerda que |quiero que recuerdes|" +
            "apunta que |anota que |no se me olvide que |toma nota de que |toma nota )",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(lower)

    private fun isMemoryForget(lower: String): Boolean = Regex(
        "\\b(olvida |borra (tu )?memoria|olvida todo|no recuerdes )",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(lower)

    private fun extractWebSearch(t: String, lower: String): String? {
        if (!Regex("\\b(en internet|en la web|en google|b[uú]scame|busca a ver)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return null
        }
        val q = t.replace(Regex("\\b(internet|en la web|en google|busca a ver|b[uú]scame|porfa|por favor)\\b", RegexOption.IGNORE_CASE), " ")
            .cleanupApp()
        return q.takeIf { it.isNotBlank() }
    }

    private fun decodeTimer(lower: String, original: String): Intent.CreateTimer? {
        val marker = Regex("\\b(temporizador|timer|cuenta atr[aá]s|record[áa]me|recu[eé]rdame|alarma en)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        if (!marker) return null
        val minutes = Regex("(\\d+)\\s*(minuto|min|mins|minutos)", RegexOption.IGNORE_CASE).find(lower)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = Regex("(\\d+)\\s*segundos?", RegexOption.IGNORE_CASE).find(lower)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (minutes == 0 && seconds == 0) return null
        return Intent.CreateTimer(minutes, seconds)
    }

    private fun decodeAlarm(lower: String, original: String): Intent.CreateAlarm? {
        if (!Regex("\\b(alarma|despi[eé]rtame)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) return null
        val hm = Regex("(\\d{1,2}):(\\d{2})\\b").find(original)
        val at = Regex("a las (\\d{1,2})(?!\\d)", RegexOption.IGNORE_CASE).find(lower)
        val inHours = Regex("(\\d{1,2}) hora(?:s)?", RegexOption.IGNORE_CASE).find(lower)
        val hours: Int
        val minutes: Int
        when {
            hm != null -> {
                hours = hm.groupValues[1].toInt().coerceIn(0, 23)
                minutes = hm.groupValues[2].toInt().coerceIn(0, 59)
            }
            at != null -> {
                hours = at.groupValues[1].toInt().coerceIn(0, 23)
                minutes = 0
            }
            inHours != null -> {
                hours = inHours.groupValues[1].toInt().coerceIn(0, 23)
                minutes = 0
            }
            else -> return null
        }
        // Label = text left after removing the recognized time phrase.
        var label = original
        hm?.let { label = label.replaceFirst(Regex(Regex.escape(it.value)), " ") }
        at?.let { label = label.replaceFirst(Regex("(?i)a las \\d{1,2}"), " ") }
        inHours?.let { label = label.replaceFirst(Regex("(?i)\\d{1,2} hora(?:s)?"), " ") }
        label = label.replace(Regex("\\b(alarma|despi[eé]rtame|pon|una|a las|para)\\b", RegexOption.IGNORE_CASE), " ")
            .cleanup()
        return Intent.CreateAlarm(hours, minutes, label.takeIf { it.isNotBlank() && it.length > 1 })
    }

    private fun String.cleanup(): String =
        replace(Regex("\\b(por fa(?:vor)?|porfavor|gracias|ahora mismo)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun String.cleanupApp(): String =
        cleanup().trimEnd('.', ',', ';')
}