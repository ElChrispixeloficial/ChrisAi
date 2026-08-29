package com.chrispixel.chrisai.data.intent

/**
 * Optional assistant presets (v0.8). They only change which system block is
 * injected into the payload; they do not spin up a second AI.
 */
enum class SessionMode(val id: String, val label: String) {
    CHAT("chat", "Conversación"),
    STUDY("study", "Modo estudio"),
    DEV("dev", "Modo programador");

    companion object {
        fun fromId(id: String?): SessionMode = entries.firstOrNull { it.id == id } ?: CHAT

        /** Loose intent detection: "modo estudio", "quiero estudiar", etc. */
        fun fromText(text: String): SessionMode? {
            val lower = text.lowercase()
            return when {
                Regex("\\b(estudio|estudiar|modo estudio|apuntes|examen|línea de tiempo|resumen de estudio)\\b").containsMatchIn(lower) ->
                    STUDY
                Regex("\\b(programador|programando|programar|código|debug|error de gradle|compi|bug|repos?)\\b").containsMatchIn(lower) ->
                    DEV
                Regex("\\b(modo conversaci[oó]n|hablar normal|chat normal)\\b").containsMatchIn(lower) ->
                    CHAT
                else -> null
            }
        }
    }

    /** System block appended to the payload while this mode is active. */
    fun systemBlock(): String = when (this) {
        CHAT -> ""
        STUDY ->
            "[MODO ESTUDIO]\nActúas como tutor de estudio: explica conceptos con claridad, " +
                "analiza imágenes de apuntes o ejercicios si el usuario las comparte, genera " +
                "preguntas de repaso y resúmenes. No sustituyes a un docente."
        DEV ->
            "[MODO PROGRAMADOR]\nActúas como asistente de programación: analiza capturas de " +
                "errores, logs, salidas de Gradle y fragmentos de código, explica la causa y " +
                "propone una solución concreta. Si el usuario comparte una imagen, analízala."
    }
}