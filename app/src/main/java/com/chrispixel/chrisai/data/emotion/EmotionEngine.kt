package com.chrispixel.chrisai.data.emotion

/**
 * v0.7 emotional intelligence system.
 *
 * ChrisAI has a simulated/computational emotional state — it never claims real
 * human emotions or consciousness. Each state carries a type, an intensity
 * (0.0..1.0), a confidence (0.0..1.0) and a timestamp. Emotions change
 * smoothly and deterministically, never randomly.
 */
data class EmotionState(
    val type: Emotion = Emotion.NEUTRAL,
    val intensity: Float = 0f,
    val confidence: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Computes the emotional state from the conversation: user mood, reply
 * content, tool outcomes and the previous state (for stable transitions).
 */
object EmotionEngine {

    const val INTENSITY_SUBTLE = 0.2f
    const val INTENSITY_MODERATE = 0.5f
    const val INTENSITY_STRONG = 0.8f
    const val INTENSITY_MAX = 1.0f

    fun neutral(): EmotionState = EmotionState(
        type = Emotion.NEUTRAL,
        intensity = 0f,
        confidence = 1f
    )

    fun generating(): EmotionState = EmotionState(
        type = Emotion.GENERATING,
        intensity = INTENSITY_STRONG,
        confidence = 1f
    )

    /** User's apparent mood from their message (helps choose response emotion). */
    fun classifyUser(text: String): Emotion {
        val lower = text.lowercase()
        val loud = text.count { it == '!' } >= 2 || text.isAllCaps()
        val results = EmotionClassifier.classifyWithScore(lower)
        if (results.second == 0) {
            return when {
                loud -> Emotion.SURPRISED
                else -> Emotion.NEUTRAL
            }
        }
        return when (results.first) {
            Emotion.SAD, Emotion.ANGRY, Emotion.WORRIED -> results.first
            Emotion.HAPPY -> if (loud) Emotion.EXCITED else Emotion.HAPPY
            Emotion.SURPRISED -> if (loud) Emotion.SURPRISED else results.first
            else -> results.first
        }
    }

    /**
     * Final emotional state after a finished reply.
     *
     * [userText], [replyText], an optional tool [toolSucceeded] outcome (null
     * when no tool ran) and the [previous] state are combined. The state stays
     * stable: low-confidence candidates don't cause random flips.
     */
    fun finalState(
        userText: String?,
        replyText: String?,
        toolSucceeded: Boolean?,
        previous: EmotionState?
    ): EmotionState {
        val now = System.currentTimeMillis()
        val userMood = userText?.let(::classifyUser) ?: Emotion.NEUTRAL
        val (replyEmotion, replyScore) = replyText?.let { EmotionClassifier.classifyWithScore(it) }
            ?: (Emotion.NEUTRAL to 0)

        // Tool outcome only shapes the result when the reply carries no signal.
        val toolEmotion: Emotion? = when (toolSucceeded) {
            true -> if (replyEmotion == Emotion.NEUTRAL) Emotion.HAPPY else null
            false -> if (replyEmotion == Emotion.NEUTRAL) Emotion.WORRIED else null
            null -> null
        }

        val candidate: Emotion = when {
            replyEmotion != Emotion.NEUTRAL -> replyEmotion
            toolEmotion != null -> toolEmotion
            userMood in setOf(Emotion.SAD, Emotion.ANGRY, Emotion.WORRIED) -> Emotion.EMPATHETIC
            userMood != Emotion.NEUTRAL -> userMood
            else -> Emotion.NEUTRAL
        }

        val confidence = candidateConfidence(candidate, replyScore)

        // Stability: never flip to a louder emotion for a weak signal.
        val base = previous?.takeIf { it.type == candidate } ?: previous
        val type = when {
            confidence >= 0.35f -> candidate
            base?.type == Emotion.NEUTRAL && candidate != Emotion.NEUTRAL -> Emotion.NEUTRAL
            base != null && base.type != Emotion.NEUTRAL -> base.type
            else -> Emotion.NEUTRAL
        }

        val intensity = deriveIntensity(type, confidence, replyScore)
        val prevIntensity = previous?.takeIf { it.type == type }?.intensity ?: intensity
        val smoothed = if (previous != null && previous.type == type) {
            lerp(prevIntensity, intensity, 0.5f)
        } else {
            intensity
        }

        return EmotionState(
            type = type,
            intensity = roundedBucket(smoothed.coerceIn(0f, 1f)),
            confidence = confidence.coerceIn(0f, 1f),
            timestamp = now
        )
    }

    /** Maps a tool result to the feeling a successful/failed action produces. */
    fun toolOutcomeEmotion(success: Boolean): EmotionState {
        val now = System.currentTimeMillis()
        return if (success) {
            EmotionState(Emotion.HAPPY, INTENSITY_SUBTLE, 0.6f, now)
        } else {
            EmotionState(Emotion.WORRIED, INTENSITY_MODERATE, 0.6f, now)
        }
    }

    private fun candidateConfidence(candidate: Emotion, replyScore: Int): Float {
        if (candidate == Emotion.NEUTRAL) return 0.9f
        return when {
            replyScore >= 3 -> 0.85f
            replyScore == 2 -> 0.65f
            replyScore == 1 -> 0.45f
            else -> 0.4f
        }
    }

    /** Continuous intensity mapped onto the spec buckets (0 / .25 / .5 / .75 / 1). */
    private fun roundedBucket(value: Float): Float {
        val buckets = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        var best = buckets[0]
        var bestDist = Float.MAX_VALUE
        for (b in buckets) {
            val d = kotlin.math.abs(value - b)
            if (d < bestDist) {
                bestDist = d
                best = b
            }
        }
        return best
    }

    private fun deriveIntensity(type: Emotion, confidence: Float, replyScore: Int): Float {
        if (type == Emotion.NEUTRAL) return 0f
        var base = when (type) {
            Emotion.HAPPY, Emotion.EXCITED, Emotion.SAD, Emotion.ANGRY -> 0.65f
            Emotion.SURPRISED, Emotion.WORRIED -> 0.55f
            Emotion.EMPATHETIC, Emotion.THOUGHTFUL -> 0.55f
            Emotion.GENERATING -> 0.8f
            Emotion.NEUTRAL -> 0f
        }
        base += replyScore * 0.08f
        base *= confidence.coerceIn(0.3f, 0.95f) / 0.5f
        return base.coerceIn(0.2f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun String.isAllCaps(): Boolean {
        if (length < 4) return false
        val letters = count { it.isLetter() }
        return letters >= 4 && letters == count { it.isUpperCase() }
    }
}