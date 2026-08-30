package com.chrispixel.chrisai.data.actions

/**
 * v0.9 ChrisTools 2.0: dynamic, dependency-light app discovery.
 *
 * Android implementations provide candidates at runtime (PackageManager);
 * fast actions and tests consume only this narrow interface.
 */
data class FoundApp(
    val label: String,
    val packageName: String
)

/** Pure matcher logic reused by the dynamic search and fast actions. */
object AppNames {

    /** Case/accent-insensitive, punctuation-normalized comparison string. */
    fun normalize(text: String): String = buildString {
        for (c in text.lowercase()) {
            val base = baseChar(c)
            if (base in 'a'..'z' || base in '0'..'9' || base == ' ') append(base)
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

    /** Bounded search: exact label > startsWith > contains > package contains. */
    fun search(candidates: List<FoundApp>, query: String, limit: Int = 5): List<FoundApp> {
        val q = normalize(query).trim()
        if (q.isEmpty()) return candidates.distinctBy { it.packageName }.take(limit)
        return candidates
            .distinctBy { it.packageName }
            .filter { isMatch(it, q) }
            .sortedWith(compareBy({ rank(it, q) }, { it.label.lowercase() }))
            .take(limit)
    }

    private fun isMatch(app: FoundApp, q: String): Boolean {
        val label = normalize(app.label)
        val pkg = normalize(app.packageName)
        return label.contains(q) || pkg.contains(q)
    }

    private fun rank(app: FoundApp, q: String): Int {
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
}

/** Abstraction over "which apps are installed" (tests use a fake list). */
fun interface AppFinder {
    fun find(query: String, limit: Int): List<FoundApp>
}