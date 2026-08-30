package com.chrispixel.chrisai.data.emotion

/**
 * v0.9 EmotionEngine 3.0 — effect layer.
 *
 * Every emotional state maps to EXACTLY ONE primary visual effect. The engine
 * distinguishes a computational state (a preference marker derived from the
 * conversation) from a human feeling: ChrisAI never claims to feel; when text
 * needs to mention it, [EmotionEngine.statePhrase] emits an honest internal
 * label or nothing at all.
 */
data class EmotionEffect(
    val emotion: Emotion,
    val name: String,
    val accentName: String
)

/** Single, deterministic primary effect per state (the UI renders one at a time). */
object EmotionEffects {

    val effects: Map<Emotion, EmotionEffect> = Emotion.entries.associate { e ->
        val accent = when (e) {
            Emotion.HAPPY -> "dorado cálido"
            Emotion.EXCITED -> "naranja vibrante"
            Emotion.NEUTRAL -> "azul neutro"
            Emotion.SAD -> "azul suave"
            Emotion.ANGRY -> "rojo tenue"
            Emotion.WORRIED -> "violeta oscuro"
            Emotion.THOUGHTFUL -> "morado"
            Emotion.SURPRISED -> "cian"
            Emotion.EMPATHETIC -> "rosa cercano"
            Emotion.GENERATING -> "azul-violeta profundo"
        }
        e to EmotionEffect(e, e.label, accent)
    }

    fun primary(emotion: Emotion): EmotionEffect = effects.getValue(emotion)
}

/** Extends the state classifier with honest, wording-safe descriptors. */
object EmotionExpression {

    /** True when the state should never be described as a feeling. */
    fun isComputational(state: EmotionState): Boolean = true

    /**
     * Optional short phrase for UI/TTS. Returns null for neutral states so we
     * don't constantly narrate internal state.
     */
    fun statePhrase(state: EmotionState): String? {
        if (state.type == Emotion.NEUTRAL || state.type == Emotion.GENERATING) return null
        val intensityLabel = when {
            state.intensity >= 0.8f -> "alta"
            state.intensity >= 0.5f -> "moderada"
            else -> "leve"
        }
        return "estado prioritario $intensityLabel: ${state.type.label.lowercase()}"
    }
}