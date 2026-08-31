package com.chrispixel.chrisai.data.model

/**
 * v1.0: independent session contexts. Each conversation carries a [SessionKind]
 * that shapes the assistant's framing without removing any existing feature:
 * [GENERAL] is the classic chat, while the rest add a bounded behavior contract
 * via the Context Engine (see data/context/ContextEngine.kt).
 */
enum class SessionKind(val id: String, val title: String) {

    /** Default classic assistant chat (v0.1..v0.9 behavior). */
    GENERAL("general", "General"),

    /** Learning/study session (didactic, step-by-step explanations). */
    STUDY("study", "Estudio"),

    /** Software development session (precise, code-first answers). */
    PROGRAMMING("programming", "Programación"),

    /** Session about the ChrisAI project itself (development discussions). */
    CHRISAI("chrisai", "Proyecto ChrisAI"),

    /** Explicit, prolonged companion session (v1.0 companion mode). */
    COMPANION("companion", "Acompañante");

    companion object {
        val DEFAULT: SessionKind = GENERAL

        /** Tolerant id parser: unknown ids fall back to the default. */
        fun fromId(value: String?): SessionKind =
            values().firstOrNull { it.id == value } ?: DEFAULT
    }
}