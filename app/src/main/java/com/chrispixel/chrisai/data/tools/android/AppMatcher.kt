package com.chrispixel.chrisai.data.tools.android

/**
 * Pure, JVM-testable matching logic for installed apps.
 *
 * Android provides the candidate list; this object ranks them.
 */
object AppMatcher {

    data class App(
        val label: String,
        val packageName: String
    )

    private fun normalize(text: String): String = buildString {
        for (c in text.lowercase()) {
            val base = baseChar(c)
            when {
                base in 'a'..'z' -> append(base)
                base in '0'..'9' -> append(base)
                base == ' ' -> append(base)
                base == '.' -> append(base)
                base == '_' -> append(base)
            }
        }
    }

    private fun baseChar(c: Char): Char = when (c) {
        'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'í', 'ì', 'î', 'ï' -> 'i'
        'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
        'ú', 'ù', 'û', 'ü' -> 'u'
        'ñ' -> 'n'
        'ç' -> 'c'
        else -> c
    }

    /** Case-insensitive, partial matching over label and package name. */
    fun normalizeTerm(query: String): String = normalize(query).trim()

    fun isMatch(app: App, query: String): Boolean {
        val q = normalizeTerm(query)
        if (q.isEmpty()) return true
        val label = normalize(app.label)
        val pkg = normalize(app.packageName)
        return label.contains(q) || pkg.contains(q)
    }

    /**
     * Ranks apps by relevance to [query]:
     * exact label > label startsWith > label contains > package contains.
     * Empty query lists everything (launcher apps preferred) up to [limit].
     */
    fun search(candidates: List<App>, query: String, limit: Int = 8): List<App> {
        val q = normalizeTerm(query)
        val out = ArrayList<App>(limit)
        if (q.isEmpty()) {
            val sorted = candidates.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            return sorted.take(limit)
        }

        fun rank(app: App): Int {
            val label = normalize(app.label)
            val pkg = normalize(app.packageName)
            return when {
                label == q -> 0
                label.startsWith(q) -> 1
                label.contains(q) -> 2
                pkg.contains(q) -> 3
                else -> 4
            }
        }

        candidates
            .distinctBy { it.packageName }
            .filter { isMatch(it, query) }
            .sortedWith(compareBy({ rank(it) }, { it.label.lowercase() }))
            .take(limit)
            .also { out.addAll(it) }
        return out
    }
}