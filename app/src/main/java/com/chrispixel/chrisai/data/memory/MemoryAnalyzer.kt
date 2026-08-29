package com.chrispixel.chrisai.data.memory

import com.chrispixel.chrisai.data.model.Memory

/**
 * Structured shape of a memory produced by [MemoryAnalyzer] (v0.8).
 * Room keeps storing the cleaned [content]; category/importance/tags travel
 * with the memory in the structured Memory.json cloud export.
 */
data class DraftMemory(
    val content: String,
    val category: String,
    val importance: Int, // 1..5
    val tags: List<String>
) {
    val isEmpty: Boolean get() = content.isBlank()
}

/**
 * Analyzes "remember X" utterances before they become permanent memory (v0.8).
 *
 * Rule-based so it works offline and deterministically. It removes the memory
 * framing ("recuerda que", "guarda esto…"), normalizes the sentence, guesses a
 * category, importance and tags — it never stores the literal framing, and it
 * helps dedupe by producing a normalized comparison key.
 */
object MemoryAnalyzer {

    private val FRAMING = Regex(
        "\\b(recuerda que|recuerda|memoriza que|memoriza|guarda esto en tu memoria|guarda esto en la memoria|" +
            "guardame esto|guarda que|quiero que recuerdes que|quiero que recuerdes|apunta que|" +
            "anota que|no se me olvide que|toma nota de que|toma nota)\\b",
        RegexOption.IGNORE_CASE
    )

    private val POLITE = Regex("\\b(por favor|porfa|porfavor|gracias|graciass?)\\b", RegexOption.IGNORE_CASE)

    private val CATEGORY_RULES = listOf(
        "proyecto" to listOf("proyecto", "app", "código", "código", "scripts", "repo", "aplicación", "github", "release", "versión", "api", "librería", "software", "sitio web", "web"),
        "técnico" to listOf("error", "bug", "fallo", "configuración", "instalar", "instalación", "compilar", "build", "gradle", "comando", "servidor", "base de datos", "latencia", "cache", "rama", "commit"),
        "personal" to listOf("familia", "amigo", "amiga", "amigos", "cumpleaños", "gusto", "me gusta", "prefiero", "hobby", "película", "serie", "música"),
        "tarea" to listOf("comprar", "hacer", "llamar", "enviar", "pendiente", "recordatorio", "mañana", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"),
        "preferencia" to listOf("no me gusta", "odio", "prefiero", "quiero", "me encanta", "tipo de", "estilo")
    )

    private val HIGH_IMPORTANCE = listOf(
        "proyecto", "contraseña", "cuenta", "importante", "crítico", "crítica", "urgente",
        "no olvides", "fundamental", "alergia", "médico", "doctor", "documento", "entregar"
    )

    private val LOW_IMPORTANCE = listOf(
        "quizá", "quizás", "tal vez", "alguna vez", "me gustaría", "podría"
    )

    /** True when [raw] is an explicit "remember" style request. */
    fun isMemoryRequest(raw: String): Boolean =
        FRAMING.containsMatchIn(raw) || Regex("\\bmemor[ií]z[aá]\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)

    /** Produces a clean structured draft, or null when [raw] is not a memory request. */
    fun analyze(raw: String): DraftMemory? {
        if (!isMemoryRequest(raw)) return null
        val content = normalizeContent(raw)
        if (content.isBlank()) return null
        return DraftMemory(
            content = content,
            category = categorize(content),
            importance = importance(content),
            tags = extractTags(content)
        )
    }

    /** Strips the framing/politeness and normalizes whitespace/punctuation. */
    fun normalizeContent(raw: String): String {
        var out = FRAMING.replace(raw, " ").trim()
        out = POLITE.replace(out, " ")
        out = out.trim(' ', ',', '.', ';', ':')
        out = out.replace(Regex("[ \t]+"), " ")
        // First letter capitalized, single trailing period.
        out = out.trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .let { if (it.endsWith(".")) it else "$it." }
        return out
    }

    fun categorize(content: String): String {
        val lower = content.lowercase()
        for ((category, words) in CATEGORY_RULES) {
            if (words.any { lower.contains(it) }) return category
        }
        return "otro"
    }

    fun importance(content: String): Int {
        val lower = content.lowercase()
        return when {
            HIGH_IMPORTANCE.any { lower.contains(it) } -> 5
            LOW_IMPORTANCE.any { lower.contains(it) } -> 2
            lower.length > 160 -> 4
            else -> 3
        }
    }

    fun extractTags(content: String): List<String> {
        val words = content.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 4 && it !in STOP_WORDS && !it.all(Char::isDigit) }
        if (words.isEmpty()) return emptyList()
        val freq = words.groupingBy { it }.eachCount()
        // Prefer capitalized words (names of projects/people), then frequency.
        return words.distinct().sortedWith(
            compareByDescending<String> { w -> if (w.first().isUpperCase()) 1 else 0 }
                .thenByDescending { w -> freq.getValue(w) }
        ).take(4)
    }

    /**
     * Normalized key for dedupe: lemmatize-lite (no diacritics, stop words,
     * stable word order). Two memories with the same key are "the same".
     */
    fun dedupeKey(content: String): String {
        val normalized = content.lowercase()
            .replace('á', 'a').replace('é', 'e').replace('í', 'i')
            .replace('ó', 'o').replace('ú', 'u').replace('ü', 'u').replace('ñ', 'n')
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.length > 2 && it !in STOP_WORDS }
            .distinct()
            .sorted()
        return normalized.joinToString(" ")
    }

    /** Detects that [existing] (a stored memory text) duplicates [draft]. */
    fun isDuplicate(existing: Memory, draft: DraftMemory): Boolean {
        if (draft.content.isBlank()) return false
        val a = dedupeKey(draft.content)
        val b = dedupeKey(existing.text)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        return existing.text.contains(draft.content, ignoreCase = true) ||
            draft.content.contains(existing.text, ignoreCase = true)
    }

    /** Simple Jaccard similarity over token sets, for "similar" recall. */
    fun similarity(a: String, b: String): Double {
        val tokensA = dedupeTokens(a)
        val tokensB = dedupeTokens(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val inter = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return inter.toDouble() / union
    }

    private fun dedupeTokens(content: String): Set<String> =
        content.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()

    private val STOP_WORDS = setOf(
        "que", "del", "con", "para", "una", "unas", "uno", "también", "este", "esta",
        "esto", "muy", "pero", "como", "cuando", "donde", "porque", "sobre", "hacia",
        "desde", "pero", "sus", "mas", "más", "los", "las", "unos", "ese", "esa", "esos",
        "puede", "pueden", "tiene", "tienen", "ser", "sido", "está", "están", "hay"
    )
}