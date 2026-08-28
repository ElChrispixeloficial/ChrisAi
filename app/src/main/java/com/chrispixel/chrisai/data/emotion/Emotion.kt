package com.chrispixel.chrisai.data.emotion

import androidx.compose.ui.graphics.Color

/**
 * v0.6 emotion system.
 *
 * A lightweight, deterministic classifier maps the assistant's finished reply
 * to one of the subjective moods below; GENERATING is a transient state shown
 * while a reply is being streamed. Each state carries a primary accent colour
 * so the UI can render exactly ONE main visual effect per state.
 */
enum class Emotion(
    val label: String,
    val accent: Color,
    val description: String
) {
    HAPPY("Feliz", Color(0xFFD4A017), "Alegría, tono dorado cálido"),
    EXCITED("Entusiasmado", Color(0xFFE67E22), "Energía, naranja vibrante"),
    NEUTRAL("Neutral", Color(0xFF3A506B), "Tema normal"),
    SAD("Triste", Color(0xFF4A90E2), "Tristeza, azul suave"),
    ANGRY("Enojado", Color(0xFFC0392B), "Frustración, rojo tenue"),
    WORRIED("Preocupado", Color(0xFF5D4E86), "Inquietud, azul oscuro/violeta"),
    THOUGHTFUL("Pensativo", Color(0xFF8E44AD), "Reflexión, morado"),
    SURPRISED("Sorprendido", Color(0xFF00B4D8), "Asombro, cian"),
    EMPATHETIC("Empático", Color(0xFFF06292), "Cercanía, rosa"),
    GENERATING("Generando", Color(0xFF5A3DB0), "Pensando, azul-violeta profundo")
}

/**
 * Classifies a finished assistant reply into an [Emotion] using a compact
 * keyword/emoji heuristic (no external model, fast and offline).
 */
object EmotionClassifier {

    private val keywords: Map<Emotion, List<String>> = mapOf(
        Emotion.HAPPY to listOf(
            "genial", "excelente", "maravilloso", "fantástico", "perfecto", "estupendo",
            "me alegra", "me alegro", "que bien", "qué bien", "alegría", "feliz", "contento",
            "me encanta", "wow", "increíble que lo", "buen trabajo", "arriba esos", "👏", "🎉", "😄", "😊", "🤗",
        ),
        Emotion.EXCITED to listOf(
            "vamos", "a por ello", "a por todas", "entusiasma", "emocionan", "emocionad",
            "no puedo esperar", "qué ganas", "qué ganas", "espectacular", "impresionante",
            "alucinante", "increíble", "una pasada", "brutal", "🎉", "🔥", "⚡", "🚀",
        ),
        Emotion.SAD to listOf(
            "lo siento", "qué pena", "que pena", "triste", "me entristece", "lamento",
            "siento mucho", "es difícil", "fue difícil", "qué difícil", "no es fácil",
            "comprendo que estés", "ánimo", "cabeza", "😢", "😞", "💔", "😔",
        ),
        Emotion.ANGRY to listOf(
            "me enfada", "me molesta", "da rabia", "frustrante", "inaceptable",
            "no me parece bien", "es indignante", "qué vergüenza", "esto no puede ser",
            "😡", "🤬", "👿",
        ),
        Emotion.WORRIED to listOf(
            "preocupad", "me preocupa", "ten cuidado", "cuidado con", "no es seguro",
            "es peligroso", "riesgo", "deberías evitar", "me da algo de", "estaré pendiente",
            "avísame", "¿estás bien?", "estás bien", "😰", "😨", "🫨",
        ),
        Emotion.THOUGHTFUL to listOf(
            "déjame pensar", "pensándolo", "puede ser", "podría ser", "es posible",
            "depende de", "por un lado", "por otro lado", "tal vez", "quizá", "quizás",
            "lo más sensato", "habría que valorar", "🤔", "🧐",
        ),
        Emotion.SURPRISED to listOf(
            "vaya", "no me lo esperaba", "qué sorpresa", "quién lo diría", "increíble que",
            "no me lo puedo creer", "no me lo creo", "asombroso", "sorprendent", "😮", "😲", "🤯", "🙀",
        ),
        Emotion.EMPATHETIC to listOf(
            "te entiendo", "te comprendo", "escuchándote", "tienes razón", "estoy contigo",
            "no estás solo", "aquí estoy", "gracias por compartir", "de nada", "me alegra ayudarte",
            "un abrazo", "cuenta conmigo", "💙", "💜", "🫂",
        ),
    )

    /** Returns the dominant emotion, or NEUTRAL when no signal is found. */
    fun classify(text: String): Emotion {
        if (text.isBlank()) return Emotion.NEUTRAL
        val lower = text.lowercase()
        // Only inspect the first part of long replies (signal is usually early).
        val sample = lower.take(1500)
        var best: Emotion = Emotion.NEUTRAL
        var bestScore = 0
        for ((emotion, words) in keywords) {
            var score = 0
            for (word in words) {
                if (word in sample) score++
            }
            if (score > bestScore) {
                bestScore = score
                best = emotion
            }
        }
        return if (bestScore > 0) best else Emotion.NEUTRAL
    }
}