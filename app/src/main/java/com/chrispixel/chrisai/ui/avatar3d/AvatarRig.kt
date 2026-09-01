package com.chrispixel.chrisai.ui.avatar3d

/**
 * v1.1 articulado skeleton of the ChrisAI android.
 *
 * Each joint owns a rotation in its parent frame; the renderer walks the tree
 * and multiplies parent-world * local so every segment moves independently
 * (torso tilt, elbow, knee, knee rings, fingers...). Offsets below define the
 * neutral T-pose/standing proportions; rotations come from [AvatarAnimator].
 */
internal enum class Joint(val parent: Joint?, val ox: Float, val oy: Float, val oz: Float) {
    ROOT(null, 0f, 1.98f, 0f),

    HIPS(ROOT, 0f, -0.32f, 0f),
    SPINE_LOWER(HIPS, 0f, 0.52f, 0f),
    SPINE_UPPER(SPINE_LOWER, 0f, 0.62f, 0f),
    CHEST(SPINE_UPPER, 0f, 0.30f, 0f),
    NECK(CHEST, 0f, 0.55f, 0f),
    HEAD(NECK, 0f, 0.18f, 0f),

    CLAV_L(CHEST, 0.52f, 0.26f, 0f),
    UPPER_ARM_L(CLAV_L, 0f, -0.42f, 0f),
    ELBOW_L(UPPER_ARM_L, 0f, -0.50f, 0f),
    FOREARM_L(ELBOW_L, 0f, -0.46f, 0f),
    WRIST_L(FOREARM_L, 0f, -0.46f, 0f),
    HAND_L(WRIST_L, 0f, -0.16f, 0f),
    THUMB_1_L(HAND_L, 0.07f, 0.02f, 0f),
    THUMB_2_L(THUMB_1_L, 0f, -0.08f, 0f),
    INDEX_1_L(HAND_L, 0.045f, -0.03f, 0f),
    INDEX_2_L(INDEX_1_L, 0f, -0.12f, 0f),
    INDEX_3_L(INDEX_2_L, 0f, -0.09f, 0f),
    MID_1_L(HAND_L, 0.012f, -0.03f, 0f),
    MID_2_L(MID_1_L, 0f, -0.13f, 0f),
    MID_3_L(MID_2_L, 0f, -0.09f, 0f),
    RING_1_L(HAND_L, -0.02f, -0.03f, 0f),
    RING_2_L(RING_1_L, 0f, -0.12f, 0f),
    RING_3_L(RING_2_L, 0f, -0.08f, 0f),
    PINKY_1_L(HAND_L, -0.05f, -0.03f, 0f),
    PINKY_2_L(PINKY_1_L, 0f, -0.10f, 0f),
    PINKY_3_L(PINKY_2_L, 0f, -0.07f, 0f),

    CLAV_R(CHEST, -0.52f, 0.26f, 0f),
    UPPER_ARM_R(CLAV_R, 0f, -0.42f, 0f),
    ELBOW_R(UPPER_ARM_R, 0f, -0.50f, 0f),
    FOREARM_R(ELBOW_R, 0f, -0.46f, 0f),
    WRIST_R(FOREARM_R, 0f, -0.46f, 0f),
    HAND_R(WRIST_R, 0f, -0.16f, 0f),
    THUMB_1_R(HAND_R, -0.07f, 0.02f, 0f),
    THUMB_2_R(THUMB_1_R, 0f, -0.08f, 0f),
    INDEX_1_R(HAND_R, -0.045f, -0.03f, 0f),
    INDEX_2_R(INDEX_1_R, 0f, -0.12f, 0f),
    INDEX_3_R(INDEX_2_R, 0f, -0.09f, 0f),
    MID_1_R(HAND_R, -0.012f, -0.03f, 0f),
    MID_2_R(MID_1_R, 0f, -0.13f, 0f),
    MID_3_R(MID_2_R, 0f, -0.09f, 0f),
    RING_1_R(HAND_R, 0.02f, -0.03f, 0f),
    RING_2_R(RING_1_R, 0f, -0.12f, 0f),
    RING_3_R(RING_2_R, 0f, -0.08f, 0f),
    PINKY_1_R(HAND_R, 0.05f, -0.03f, 0f),
    PINKY_2_R(PINKY_1_R, 0f, -0.10f, 0f),
    PINKY_3_R(PINKY_2_R, 0f, -0.07f, 0f),

    HIP_L(HIPS, 0.22f, -0.02f, 0f),
    KNEE_L(HIP_L, 0f, -0.66f, 0f),
    ANKLE_L(KNEE_L, 0f, -0.72f, 0f),
    HIP_R(HIPS, -0.22f, -0.02f, 0f),
    KNEE_R(HIP_R, 0f, -0.66f, 0f),
    ANKLE_R(KNEE_R, 0f, -0.72f, 0f);

    companion object {
        val all: List<Joint> = entries
    }
}

/** Live stage + emotion controls, mapped to the skeleton + face by the animator. */
internal data class AvatarFaceState(
    val mouthOpen: Float = 0.12f,
    val eyeOpen: Float = 1f,
    val showThinking: Boolean = false,
    val thinkingWave: Float = 0f,
    val listeningArc: Float = 0f,
    val lookingDown: Boolean = false
)

/** Pure, deterministic output of one animation tick. */
internal data class AvatarPose(
    val rotations: FloatArray,
    val face: AvatarFaceState,
    val emotionMix: Float,          // 0..1
    val emotionR: Float, val emotionG: Float, val emotionB: Float
)

internal object AvatarColors {
    val WHITE = floatArrayOf(0.95f, 0.965f, 0.99f)
    val WHITE_DIM = floatArrayOf(0.87f, 0.89f, 0.94f)
    val GRAY = floatArrayOf(0.66f, 0.70f, 0.75f)
    val DARK = floatArrayOf(0.10f, 0.12f, 0.15f)
    val CYAN = floatArrayOf(0.13f, 0.83f, 0.93f)
    val RED = floatArrayOf(1.0f, 0.42f, 0.42f)
}