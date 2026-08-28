package com.chrispixel.chrisai.data.local

/**
 * v0.4 memory intents.
 *
 * - Explicit Spanish commands ("recuerda que…", "acuérdate de…", "memoriza…",
 *   "olvida…", "muéstrame tus recuerdos") are handled locally, no API call.
 * - The model can also decide what to persist: it appends `[MEMORIA: …]`
 *   / `[OLVIDA: …]` tags and [parseTags] stores them and hides them from the UI.
 */
object MemoryIntent {

    data class Tagged(val cleaned: String, val toSave: List<String>, val toForget: List<String>)

    private val SAVE_STEMS = listOf(
        "recu[eé]rda que", "recu[eé]rda esto", "acu[eé]rdame que", "acu[eé]rdame",
        "acu[eé]rdate de", "acu[eé]rdate que", "acu[eé]rdate", "memoriza",
        "no olvides que", "no olvides", "guarda que", "guarda esto"
    )

    private val FORGET_STEMS = listOf(
        "olvida que", "olvida", "deja de recordar que", "deja de recordar",
        "borra de tu memoria que", "borra de tu memoria", "quita de tu memoria que", "quita de tu memoria"
    )

    private val SAVE_PATTERNS = SAVE_STEMS.map { Regex("^\\s*" + it + "\\b", RegexOption.IGNORE_CASE) }
    private val FORGET_PATTERNS = FORGET_STEMS.map { Regex("^\\s*" + it + "\\b", RegexOption.IGNORE_CASE) }

    private val SAVE_TAG = Regex("\\[MEMORIA:\\s*([^\\]]+)\\]", RegexOption.IGNORE_CASE)
    private val FORGET_TAG = Regex("\\[OLVIDA:\\s*([^\\]]+)\\]", RegexOption.IGNORE_CASE)

    /** If [text] is an explicit "remember" command, returns the text to store. */
    fun saveText(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.length < 8) return null
        return extractAfter(SAVE_PATTERNS, trimmed)?.takeIf { it.length >= 3 }
    }

    /** If [text] is an explicit "forget" command, returns the search text. */
    fun forgetText(input: String): String? {
        return extractAfter(FORGET_PATTERNS, input.trim())?.takeIf { it.length >= 2 }
    }

    /** True when the user asks to wipe the whole memory. */
    fun forgetAll(input: String): Boolean {
        val t = input.trim().lowercase().removeSuffix("?").removeSuffix(".").trim()
        val stems = listOf(
            "olvida todo", "olvidalo todo", "olvida todos tus recuerdos", "borra todos tus recuerdos",
            "borra toda tu memoria", "vacia tu memoria", "deja de recordar todo"
        )
        return stems.any { t == it }
    }

    /** True when the user asks to see their memories. */
    fun listRequested(input: String): Boolean {
        val t = input.trim()
            .lowercase()
            .removePrefix("¿")
            .removePrefix("¡")
            .trimEnd('?', '.', '!', ' ')
            .trim()
        val phrases = listOf(
            "qué recuerdas", "que recuerdas", "qué recuerdos tienes", "que recuerdos tienes",
            "muéstrame tus recuerdos", "muestrame tus recuerdos", "muéstrame la memoria",
            "muestrame la memoria", "ver memoria", "ver recuerdos", "lista de recuerdos",
            "memoria", "memorias", "recuerdos"
        )
        return phrases.any { t == it }
    }

    /** Extracts and strips [MEMORIA:…]/[OLVIDA:…] tags added by the model. */
    fun parseTags(content: String): Tagged {
        val toSave = extractAll(content, SAVE_TAG)
        val toForget = extractAll(content, FORGET_TAG)
        var cleaned = content
        cleaned = cleaned.replace(SAVE_TAG, " ")
        cleaned = cleaned.replace(FORGET_TAG, " ")
        cleaned = cleaned.replace(Regex("\\n{3,}"), "\n\n").trim()
        return Tagged(cleaned = cleaned, toSave = toSave, toForget = toForget)
    }

    private fun extractAfter(patterns: List<Regex>, input: String): String? {
        for (pattern in patterns) {
            val match = pattern.find(input) ?: continue
            val index = match.range.last + 1
            if (index >= input.length) return null
            val rest = input.substring(index)
            val cleaned = rest.trim().trimStart(':', '-', ' ').trim().removePrefix("que").trim()
            if (cleaned.isEmpty()) return null
            return cleaned
        }
        return null
    }

    private fun extractAll(content: String, tag: Regex): List<String> =
        tag.findAll(content).mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf { s -> s.isNotEmpty() } }.toList()
}