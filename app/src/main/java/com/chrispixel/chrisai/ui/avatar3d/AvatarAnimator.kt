package com.chrispixel.chrisai.ui.avatar3d

import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.live.LiveStage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure, deterministic animator: maps the live [LiveStage] + [Emotion]/
 * intensity into absolute joint rotations (degrees) and a face state.
 * Stateless on purpose — the GL renderer smooth-lerps the current pose toward
 * each target so transitions (incl. barge-in) look natural.
 */
internal object AvatarAnimator {

    private const val DEG = 180f / PI.toFloat()

    /** Set a joint euler (rx, ry, rz in degrees) into the pose array. */
    private fun set(a: FloatArray, j: Joint, rx: Float, ry: Float, rz: Float) {
        val o = j.ordinal * 3
        a[o] = rx; a[o + 1] = ry; a[o + 2] = rz
    }

    private fun get(a: FloatArray, j: Joint): Float = a[j.ordinal * 3]

    val xyz: Int get() = Joint.entries.size * 3

    fun buildIdlePose(): FloatArray {
        val a = FloatArray(Joint.entries.size * 3)
        set(a, Joint.HEAD, -2f, 0f, 0f)
        set(a, Joint.UPPER_ARM_L, 0f, 0f, -5f)
        set(a, Joint.UPPER_ARM_R, 0f, 0f, 5f)
        set(a, Joint.FOREARM_L, 0f, 4f, 0f)
        set(a, Joint.FOREARM_R, 0f, -4f, 0f)
        // relaxed fingers: gentle curl toward the palm.
        for (j in listOf(Joint.INDEX_1_L, Joint.MID_1_L, Joint.RING_1_L, Joint.PINKY_1_L)) set(a, j, -8f, 0f, 0f)
        for (j in listOf(Joint.INDEX_2_L, Joint.MID_2_L, Joint.RING_2_L, Joint.PINKY_2_L)) set(a, j, -12f, 0f, 0f)
        for (j in listOf(Joint.INDEX_3_L, Joint.MID_3_L, Joint.RING_3_L, Joint.PINKY_3_L)) set(a, j, -15f, 0f, 0f)
        for (j in listOf(Joint.INDEX_1_R, Joint.MID_1_R, Joint.RING_1_R, Joint.PINKY_1_R)) set(a, j, -8f, 0f, 0f)
        for (j in listOf(Joint.INDEX_2_R, Joint.MID_2_R, Joint.RING_2_R, Joint.PINKY_2_R)) set(a, j, -12f, 0f, 0f)
        for (j in listOf(Joint.INDEX_3_R, Joint.MID_3_R, Joint.RING_3_R, Joint.PINKY_3_R)) set(a, j, -15f, 0f, 0f)
        set(a, Joint.THUMB_1_L, -6f, 0f, 18f)
        set(a, Joint.THUMB_2_L, -9f, 0f, 6f)
        set(a, Joint.THUMB_1_R, -6f, 0f, -18f)
        set(a, Joint.THUMB_2_R, -9f, 0f, -6f)
        set(a, Joint.ELBOW_L, -5f, 0f, 0f)
        set(a, Joint.ELBOW_R, -5f, 0f, 0f)
        set(a, Joint.ANKLE_L, 0f, 0f, 0f)
        set(a, Joint.ANKLE_R, 0f, 0f, 0f)
        return a
    }

    private fun curlAll(a: FloatArray, depth: Float) {
        val dp1 = -8f * depth
        val dp2 = -12f * depth
        val dp3 = -15f * depth
        for (j in listOf(Joint.INDEX_1_L, Joint.MID_1_L, Joint.RING_1_L, Joint.PINKY_1_L,
            Joint.INDEX_1_R, Joint.MID_1_R, Joint.RING_1_R, Joint.PINKY_1_R)) set(a, j, dp1, 0f, 0f)
        for (j in listOf(Joint.INDEX_2_L, Joint.MID_2_L, Joint.RING_2_L, Joint.PINKY_2_L,
            Joint.INDEX_2_R, Joint.MID_2_R, Joint.RING_2_R, Joint.PINKY_2_R)) set(a, j, dp2, 0f, 0f)
        for (j in listOf(Joint.INDEX_3_L, Joint.MID_3_L, Joint.RING_3_L, Joint.PINKY_3_L,
            Joint.INDEX_3_R, Joint.MID_3_R, Joint.RING_3_R, Joint.PINKY_3_R)) set(a, j, dp3, 0f, 0f)
        set(a, Joint.THUMB_1_L, -6f * depth, 0f, 18f)
        set(a, Joint.THUMB_2_L, -9f * depth, 0f, 6f)
        set(a, Joint.THUMB_1_R, -6f * depth, 0f, -18f)
        set(a, Joint.THUMB_2_R, -9f * depth, 0f, -6f)
    }

    private fun emotionRgb(e: Emotion): FloatArray = when (e) {
        Emotion.HAPPY -> floatArrayOf(0.83f, 0.63f, 0.09f)
        Emotion.EXCITED -> floatArrayOf(0.90f, 0.49f, 0.13f)
        Emotion.NEUTRAL -> floatArrayOf(0f, 0f, 0f)
        Emotion.SAD -> floatArrayOf(0.29f, 0.56f, 0.89f)
        Emotion.ANGRY -> floatArrayOf(0.75f, 0.22f, 0.17f)
        Emotion.WORRIED -> floatArrayOf(0.36f, 0.31f, 0.53f)
        Emotion.THOUGHTFUL -> floatArrayOf(0.56f, 0.27f, 0.68f)
        Emotion.SURPRISED -> floatArrayOf(0.0f, 0.71f, 0.85f)
        Emotion.EMPATHETIC -> floatArrayOf(0.94f, 0.38f, 0.57f)
        Emotion.GENERATING -> floatArrayOf(0.35f, 0.24f, 0.69f)
    }

    fun pose(
        stage: LiveStage?,
        emotion: Emotion,
        intensity: Float,
        timeMs: Long
    ): AvatarPose {
        val t = timeMs.toFloat()
        val a = buildIdlePose()
        var eyeOpen = 1f
        var mouth = 0.12f
        var showThinking = false
        var listeningArc = 0f
        var lookDown = false
        var emotionMix = if (emotion == Emotion.NEUTRAL || emotion == Emotion.GENERATING) {
            0f
        } else {
            min(1f, intensity.coerceIn(0f, 1f))
        }
        val headBaseRx = get(a, Joint.HEAD)

        // idle micro-motion: breathing sway + soft pendulum.
        set(a, Joint.SPINE_LOWER, 0f, 0f, 0.8f * sin(t * 0.0011f))
        set(a, Joint.SPINE_UPPER, 0f, 0f, -0.8f * sin(t * 0.0011f))
        set(a, Joint.HIPS, 0f, 1.1f * sin(t * 0.00045f), 0f)
        set(a, Joint.HEAD, headBaseRx + 0.8f * sin(t * 0.0007f), 0f, 0f)

        when (stage) {
            LiveStage.LISTENING -> {
                set(a, Joint.SPINE_UPPER, 5f, 0f, get(a, Joint.SPINE_UPPER))
                set(a, Joint.NECK, 4f, 0f, 0f)
                listeningArc = 0.5f + 0.5f * sin(t * 0.012f)
                set(a, Joint.HEAD, -1f, 6f * sin(t * 0.002f), 0f)
                eyeOpen = 1f
                mouth = 0.14f
                lookDown = false
            }
            LiveStage.THINKING, LiveStage.GENERATING -> {
                showThinking = true
                set(a, Joint.SPINE_UPPER, -3f, 0f, 0f)
                set(a, Joint.HEAD, -3f, 9f, 3f)
                set(a, Joint.UPPER_ARM_R, -12f, 0f, -7f)
                set(a, Joint.ELBOW_R, -105f, 0f, 0f)
                eyeOpen = 0.9f
                mouth = 0.14f
            }
            LiveStage.SPEAKING -> {
                val s = (sin(t * 0.045f) + 1f) / 2f
                mouth = 0.16f + 0.5f * s
                set(a, Joint.HEAD, 2f + 1.5f * sin(t * 0.045f), 0f, 0f)
                // gentle right-hand gesture while talking.
                set(a, Joint.UPPER_ARM_R, -4f, 0f, -12f + 6f * sin(t * 0.009f))
                set(a, Joint.ELBOW_R, -62f - 18f * sin(t * 0.009f), 0f, 0f)
                eyeOpen = 1f
            }
            LiveStage.INTERRUPTED -> {
                set(a, Joint.SPINE_UPPER, -8f, 0f, 2f)
                set(a, Joint.HEAD, 6f, 3f, 0f)
                set(a, Joint.UPPER_ARM_L, 6f, 0f, -18f)
                set(a, Joint.UPPER_ARM_R, 6f, 0f, 18f)
                eyeOpen = 1.25f
                mouth = 0.25f
            }
            LiveStage.ERROR -> {
                lookDown = true
                emotionMix = 0.75f // handled below with red
                set(a, Joint.HEAD, -9f, 0f, 0f)
                set(a, Joint.CLAV_L, -5f, 0f, 0f)
                set(a, Joint.CLAV_R, -5f, 0f, 0f)
                eyeOpen = 0.8f
                mouth = 0.22f
            }
            null, LiveStage.IDLE -> {
                // base + emotion shaping below
            }
        }

        // Emotion-driven shaping on top of the stage pose.
        when (emotion) {
            Emotion.HAPPY -> {
                set(a, Joint.HEAD, get(a, Joint.HEAD), 0f, 3f)
                set(a, Joint.UPPER_ARM_L, 0f, 0f, -7f)
                set(a, Joint.UPPER_ARM_R, 0f, 0f, 7f)
            }
            Emotion.EXCITED -> {
                set(a, Joint.SPINE_UPPER, 0f, 0f, 1.5f * sin(t * 0.006f))
                set(a, Joint.UPPER_ARM_L, -4f, 0f, -14f)
                set(a, Joint.UPPER_ARM_R, -4f, 0f, 14f)
                eyeOpen = 1.12f
            }
            Emotion.SAD -> {
                set(a, Joint.HEAD, -8f, 0f, 0f)
                set(a, Joint.UPPER_ARM_L, 7f, 0f, -5f)
                set(a, Joint.UPPER_ARM_R, 7f, 0f, 5f)
                eyeOpen = 0.85f
                mouth = 0.24f
                lookDown = true
            }
            Emotion.ANGRY -> {
                curlAll(a, 2.2f)
                set(a, Joint.HEAD, -5f, 0f, 0f)
                set(a, Joint.SPINE_UPPER, 4f, 0f, 0f)
                set(a, Joint.UPPER_ARM_L, 6f, 0f, -4f)
                set(a, Joint.UPPER_ARM_R, 6f, 0f, 4f)
                eyeOpen = 0.95f
                mouth = 0.34f
            }
            Emotion.WORRIED -> {
                set(a, Joint.HEAD, -4f, 0f, 0f)
                set(a, Joint.UPPER_ARM_L, 3f, 0f, -8f)
                set(a, Joint.UPPER_ARM_R, 3f, 0f, 8f)
                set(a, Joint.ELBOW_L, -20f, 0f, 0f)
                set(a, Joint.ELBOW_R, -20f, 0f, 0f)
                eyeOpen = 1.05f
                mouth = 0.28f
            }
            Emotion.THOUGHTFUL -> {
                set(a, Joint.HEAD, -3f, 6f, 2f)
                eyeOpen = 0.85f
            }
            Emotion.SURPRISED -> {
                set(a, Joint.HEAD, -6f, 0f, 0f)
                set(a, Joint.UPPER_ARM_L, 18f, 0f, -20f)
                set(a, Joint.UPPER_ARM_R, 18f, 0f, 20f)
                set(a, Joint.ELBOW_L, -30f, 0f, 0f)
                set(a, Joint.ELBOW_R, -30f, 0f, 0f)
                eyeOpen = 1.3f
                mouth = 0.36f
            }
            Emotion.EMPATHETIC -> {
                set(a, Joint.SPINE_UPPER, 3f, 0f, 0f)
                set(a, Joint.HEAD, 0f, 0f, -4f)
                eyeOpen = 1.05f
            }
            Emotion.GENERATING, Emotion.NEUTRAL -> { /* stage already covers */ }
        }

        // Blink: a short dip near the end of each 4.2s cycle.
        val cycle = (timeMs % 4200L) / 4200f
        if (cycle > 0.94f) {
            val dip = max(0.06f, 1f - (cycle - 0.94f) / 0.06f)
            eyeOpen *= dip
        }

        val face = AvatarFaceState(
            mouthOpen = mouth,
            eyeOpen = eyeOpen,
            showThinking = showThinking,
            thinkingWave = sin(t * 0.02f),
            listeningArc = listeningArc,
            lookingDown = lookDown
        )
        val rgb = if (stage == LiveStage.ERROR) floatArrayOf(1f, 0.42f, 0.42f) else emotionRgb(emotion)
        return AvatarPose(a, face, emotionMix, rgb[0], rgb[1], rgb[2])
    }
}