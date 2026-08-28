package com.chrispixel.chrisai.data.update

/**
 * Strict semantic versioning ("X.Y.Z") used to decide whether a release is
 * newer than the installed build. Handles an optional leading "v" and ignores
 * pre-release/build suffixes for the comparison.
 */
data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemanticVersion> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "Versión inválida" }
    }

    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {

        private val regex = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:[.\-+].*)?$""")

        fun parse(raw: String): SemanticVersion? {
            val match = regex.find(raw.trim()) ?: return null
            val groups = match.groupValues
            return try {
                SemanticVersion(
                    major = groups[1].toInt(),
                    minor = groups[2].toInt(),
                    patch = groups[3].toInt()
                )
            } catch (_: Exception) {
                null
            }
        }

        /** True when [candidate] is strictly newer than [installed]. */
        fun isNewer(installed: String, candidate: String): Boolean {
            val current = parse(installed) ?: return false
            val next = parse(candidate) ?: return false
            return next > current
        }
    }
}