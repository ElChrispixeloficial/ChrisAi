package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.privacy.PrivacyFeature
import com.chrispixel.chrisai.data.privacy.PrivacyGuard
import com.chrispixel.chrisai.data.privacy.PrivacyPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGuardTest {

    @Test
    fun `privacy mode blocks camera vision screen and drive`() {
        val policy = PrivacyGuard.policy(privacyMode = true)
        assertTrue(policy.privacyModeEnabled)
        assertFalse(policy.allows(PrivacyFeature.CAMERA))
        assertFalse(policy.allows(PrivacyFeature.VISION))
        assertFalse(policy.allows(PrivacyFeature.SCREEN_SHARE))
        assertFalse(policy.allows(PrivacyFeature.DRIVE_SYNC))
        assertFalse(policy.allows(PrivacyFeature.AUTO_LISTEN))
    }

    @Test
    fun `without privacy mode defaults are permissive`() {
        val policy = PrivacyGuard.policy(privacyMode = false, visionEnabled = true, driveSyncEnabled = true)
        assertTrue(policy.allows(PrivacyFeature.CAMERA))
        assertTrue(policy.allows(PrivacyFeature.VISION))
        assertTrue(policy.allows(PrivacyFeature.SCREEN_SHARE))
        assertTrue(policy.allows(PrivacyFeature.DRIVE_SYNC))
        assertTrue(policy.allows(PrivacyFeature.AUTO_LISTEN))
    }

    @Test
    fun `vision off disables vision but keeps camera`() {
        val policy = PrivacyGuard.policy(privacyMode = false, visionEnabled = false)
        assertFalse(policy.allows(PrivacyFeature.VISION))
        assertTrue(policy.allows(PrivacyFeature.CAMERA))
    }

    @Test
    fun `auto listen requires the mic`() {
        val noMic = PrivacyPolicy(micAllowed = false)
        assertFalse(noMic.allows(PrivacyFeature.AUTO_LISTEN))
    }

    @Test
    fun `temp files retention is disabled in privacy mode`() {
        val p = PrivacyGuard.policy(privacyMode = true)
        assertFalse(p.allows(PrivacyFeature.TEMP_FILES))
    }

    @Test
    fun `canUse helper matches allows`() {
        val policy = PrivacyGuard.policy(privacyMode = true)
        assertFalse(PrivacyGuard.canUse(policy, PrivacyFeature.CAMERA))
    }
}