package com.chrispixel.chrisai.data.haptics

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import com.chrispixel.chrisai.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * v0.6 subtle haptic feedback.
 *
 * "Fragmento -> pulso": each streamed fragment triggers an extremely subtle,
 * short pulse. Pulses are throttled (never a continuous vibration) and respect
 * both the in-app toggle and the system haptic setting.
 */
class Haptics(
    private val context: Context,
    private val settings: SettingsRepository
) {

    private var vibrator: Vibrator? = null
    private var lastPulseAt = 0L

    private val hapticsEnabled: Boolean
        get() = settings.hapticsEnabled.value

    /** Subtle pulse for a streamed fragment (throttled). */
    fun pulse() {
        if (!hapticsEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastPulseAt < THROTTLE_MS) return
        lastPulseAt = now
        if (!systemHapticsOn()) return
        val vibrator = ensureVibrator() ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(PULSE_MS, PULSE_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(PULSE_MS)
            }
        } catch (_: Throwable) {
            // ignore: haptics are never critical
        }
    }

    /** Stronger but still short pulse for a completed action (message sent). */
    fun confirm() {
        if (!hapticsEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastPulseAt < CONFIRM_THROTTLE_MS) return
        lastPulseAt = now
        if (!systemHapticsOn()) return
        val vibrator = ensureVibrator() ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(CONFIRM_MS, CONFIRM_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(CONFIRM_MS)
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    /**
     * Discrete two-tap pattern for a failed tool action or error feedback.
     * Short, never continuous, and always optional.
     */
    fun error() {
        if (!hapticsEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastPulseAt < ERROR_THROTTLE_MS) return
        lastPulseAt = now
        if (!systemHapticsOn()) return
        val vibrator = ensureVibrator() ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, ERROR_PULSE_MS, ERROR_GAP_MS, ERROR_PULSE_MS),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, ERROR_PULSE_MS, ERROR_GAP_MS, ERROR_PULSE_MS), -1)
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun ensureVibrator(): Vibrator? {
        if (vibrator == null) {
            vibrator = try {
                context.getSystemService(Vibrator::class.java)
            } catch (_: Throwable) {
                null
            } ?: @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        return vibrator?.takeIf { it.hasVibrator() }
    }

    private fun systemHapticsOn(): Boolean = try {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1
        ) != 0
    } catch (_: Throwable) {
        true
    }

    private companion object {
        const val THROTTLE_MS = 250L
        const val CONFIRM_THROTTLE_MS = 400L
        const val ERROR_THROTTLE_MS = 500L
        const val ERROR_PULSE_MS = 25L
        const val ERROR_GAP_MS = 60L
        const val PULSE_MS = 18L
        const val CONFIRM_MS = 30L
        const val PULSE_AMPLITUDE = 40
        const val CONFIRM_AMPLITUDE = 60
    }
}