package com.chrispixel.chrisai.data.privacy

/** Capabilities that Privacy Mode can gate (v0.8). */
enum class PrivacyFeature {
    CAMERA,
    VISION,          // periodic captures / screenshots attached during Live
    SCREEN_SHARE,    // MediaProjection screen capture
    MIC,
    AUTO_LISTEN,     // continuous listening (Live Assistant)
    DRIVE_SYNC,
    TEMP_FILES       // temporary captures retention
}

/** Effective policy computed by [PrivacyGuard] from the current settings. */
data class PrivacyPolicy(
    val privacyModeEnabled: Boolean = false,
    val cameraAllowed: Boolean = true,
    val visionAllowed: Boolean = true,
    val screenShareAllowed: Boolean = true,
    val micAllowed: Boolean = true,
    val autoListenAllowed: Boolean = true,
    val driveSyncAllowed: Boolean = true,
    val captureRetentionMillis: Long = 0L
) {
    fun allows(feature: PrivacyFeature): Boolean = when (feature) {
        PrivacyFeature.CAMERA -> cameraAllowed
        PrivacyFeature.VISION -> visionAllowed
        PrivacyFeature.SCREEN_SHARE -> screenShareAllowed
        PrivacyFeature.MIC -> micAllowed
        PrivacyFeature.AUTO_LISTEN -> autoListenAllowed && micAllowed
        PrivacyFeature.DRIVE_SYNC -> driveSyncAllowed
        PrivacyFeature.TEMP_FILES -> !privacyModeEnabled
    }
}

/**
 * Explicit privacy envelope (v0.8). With Privacy Mode ON: camera, vision,
 * screen share and Drive sync are blocked, temporary captures are not kept,
 * the mic only stays on when a session explicitly needs it, and the engine is
 * told not to auto-listen after speech (each turn needs an explicit tap).
 */
object PrivacyGuard {

    /** Applies Privacy Mode over the base preferences. */
    fun policy(
        privacyMode: Boolean,
        visionEnabled: Boolean = true,
        driveSyncEnabled: Boolean = false,
        captureRetentionMillis: Long = 0L
    ): PrivacyPolicy {
        val forced = if (privacyMode) {
            PrivacyPolicy(
                privacyModeEnabled = true,
                cameraAllowed = false,
                visionAllowed = false,
                screenShareAllowed = false,
                micAllowed = true,
                autoListenAllowed = false,
                driveSyncAllowed = false,
                captureRetentionMillis = 0L
            )
        } else {
            PrivacyPolicy(
                privacyModeEnabled = false,
                cameraAllowed = true,
                visionAllowed = visionEnabled,
                screenShareAllowed = true,
                micAllowed = true,
                autoListenAllowed = true,
                driveSyncAllowed = driveSyncEnabled,
                captureRetentionMillis = captureRetentionMillis
            )
        }
        return forced
    }

    /** Convenience: can the session start continuous listening right now? */
    fun canUse(policy: PrivacyPolicy, feature: PrivacyFeature): Boolean = policy.allows(feature)
}