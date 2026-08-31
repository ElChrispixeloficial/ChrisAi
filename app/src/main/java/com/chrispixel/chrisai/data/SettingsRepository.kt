package com.chrispixel.chrisai.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chrispixel.chrisai.BuildConfig
import com.chrispixel.chrisai.data.model.AiModel
import com.chrispixel.chrisai.data.personality.PersonalityConfig
import com.chrispixel.chrisai.nativebridge.NativeCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore(name = "chrisai_settings")

/**
 * Settings persisted in DataStore (async, reactive).
 *
 * The OpenRouter API key ships with the build (BuildConfig, populated from
 * local.properties) so the user never has to type it. A runtime override can be
 * stored encrypted with the native ChaCha20 module and is then preferred.
 */
class SettingsRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {

    /** Active API key: runtime override (decrypted) or the bundled build key. */
    val apiKey: StateFlow<String> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_API_KEY]?.let(::decryptKey) }
        .map { it?.takeIf(String::isNotBlank) ?: BuildConfig.OPENROUTER_API_KEY }
        .stateIn(scope, SharingStarted.Eagerly, BuildConfig.OPENROUTER_API_KEY)

    val model: StateFlow<String> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_MODEL]?.takeIf(String::isNotBlank) ?: BuildConfig.DEFAULT_MODEL }
        .stateIn(scope, SharingStarted.Eagerly, BuildConfig.DEFAULT_MODEL)

    val temperature: StateFlow<Double> = context.settingsDataStore.data
        .map { prefs -> (prefs[KEY_TEMPERATURE] ?: DEFAULT_TEMPERATURE.toFloat()).toDouble() }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_TEMPERATURE)

    val availableModels: StateFlow<List<AiModel>> = context.settingsDataStore.data
        .map { prefs -> parseCachedModels(prefs[KEY_MODELS_CACHE]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** v0.6 personality configuration. */
    val personality: StateFlow<PersonalityConfig> = context.settingsDataStore.data
        .map { prefs ->
            PersonalityConfig(
                name = prefs[KEY_PERSONALITY_NAME]?.takeIf { it.isNotBlank() } ?: "ChrisAI",
                presetId = prefs[KEY_PERSONALITY_PRESET]?.takeIf { it.isNotBlank() } ?: "casual",
                humorLevel = prefs[KEY_PERSONALITY_HUMOR]?.coerceIn(1, 5) ?: 2,
                detailLevel = prefs[KEY_PERSONALITY_DETAIL]?.coerceIn(1, 5) ?: 2,
                communicationStyle = prefs[KEY_PERSONALITY_STYLE].orEmpty(),
                customInstructions = prefs[KEY_PERSONALITY_CUSTOM].orEmpty()
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, PersonalityConfig())

    /** Subtle haptic feedback (v0.6), on by default. */
    val hapticsEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_HAPTICS_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** Emotion/animation effects (v0.6), on by default. */
    val animationsEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_ANIMATIONS_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** Read replies aloud with TTS automatically after streaming (v0.6). */
    val autoRead: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_AUTO_READ] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** TTS enabled at all (v0.6). */
    val ttsEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_TTS_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** TTS speech rate multiplier (0.5..2.0). */
    val ttsRate: StateFlow<Float> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_TTS_RATE]?.coerceIn(0.5f, 2.0f) ?: 1.0f }
        .stateIn(scope, SharingStarted.Eagerly, 1.0f)

    /** TTS pitch multiplier (0.5..2.0, v0.7). */
    val ttsPitch: StateFlow<Float> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_TTS_PITCH]?.coerceIn(0.5f, 2.0f) ?: 1.0f }
        .stateIn(scope, SharingStarted.Eagerly, 1.0f)

    /** Preferred TTS voice name (empty = system default). */
    val ttsVoice: StateFlow<String> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_TTS_VOICE].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    /** Preferred STT input language (empty = system default). */
    val sttLanguage: StateFlow<String> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_STT_LANGUAGE].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    /** v0.8.1: voice call mode (continuous conversation loop), on by default. */
    val callModeEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_CALL_MODE_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** v0.8.1: spoken greeting when a call starts, on by default. */
    val callGreetingEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_CALL_GREETING_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** v0.8.1: keep listening automatically after each reply, on by default. */
    val callContinuousEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_CALL_CONTINUOUS_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** v0.8.1: send images/vision messages, on by default. */
    val imagesEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_IMAGES_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** v0.9: knowledge/study mode (pedagogical contract for the model). */
    val studyModeEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_STUDY_MODE] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** v0.9: periodic visual capture interval (seconds, bounded 2..60). */
    val captureIntervalSec: StateFlow<Int> = context.settingsDataStore.data
        .map { prefs -> (prefs[KEY_CAPTURE_INTERVAL] ?: 5).coerceIn(2, 60) }
        .stateIn(scope, SharingStarted.Eagerly, 5)

    // ------------------------------------------------------------ v1.0 flags

    /** v1.0: first-run onboarding completed (Google Drive or "sin sincronización"). */
    val onboardingCompleted: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_ONBOARDING_DONE] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** v1.0: Drive synchronization opt-in (off by default, existing installs safe). */
    val driveSyncEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_DRIVE_SYNC_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** v1.0: email of the Google account authorized for Drive ("" = none). */
    val driveAccountEmail: StateFlow<String> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_DRIVE_ACCOUNT].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    init {
        scope.launch { bootstrap() }
    }

    /** Legacy preference import + one-shot v1.0 migration (existing users skip onboarding). */
    private suspend fun bootstrap() {
        migrateLegacyPreferences()
        val prefs = context.settingsDataStore.data.first()
        if (!prefs.contains(KEY_ONBOARDING_DONE) && hasAppUsageEvidence(prefs)) {
            context.settingsDataStore.edit { it[KEY_ONBOARDING_DONE] = true }
        }
    }

    /** True when the DataStore already carries any ChrisAI setting (previous usage). */
    private fun hasAppUsageEvidence(prefs: androidx.datastore.preferences.core.Preferences): Boolean =
        prefs.asMap().isNotEmpty()

    suspend fun setApiKey(value: String) {
        val trimmed = value.trim()
        val blob = if (trimmed.isBlank()) "" else NativeCrypto.encrypt(trimmed)
        context.settingsDataStore.edit { it[KEY_API_KEY] = blob }
    }

    suspend fun setSelectedModel(value: String) {
        context.settingsDataStore.edit { it[KEY_MODEL] = value.trim() }
    }

    suspend fun setTemperature(value: Double) {
        context.settingsDataStore.edit { it[KEY_TEMPERATURE] = value.coerceIn(0.0, 1.0).toFloat() }
    }

    suspend fun saveCachedModels(models: List<AiModel>) {
        context.settingsDataStore.edit { it[KEY_MODELS_CACHE] = encodeModels(models) }
    }

    // ------------------------------------------------------------------ v0.6

    suspend fun setPersonality(config: PersonalityConfig) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_PERSONALITY_NAME] = config.name.trim().take(30)
            prefs[KEY_PERSONALITY_PRESET] = config.presetId
            prefs[KEY_PERSONALITY_HUMOR] = config.humorLevel.coerceIn(1, 5)
            prefs[KEY_PERSONALITY_DETAIL] = config.detailLevel.coerceIn(1, 5)
            prefs[KEY_PERSONALITY_STYLE] = config.communicationStyle.trim().take(120)
            prefs[KEY_PERSONALITY_CUSTOM] = config.customInstructions.trim().take(800)
        }
    }

    suspend fun setPersonalityName(name: String) {
        context.settingsDataStore.edit { it[KEY_PERSONALITY_NAME] = name.trim().take(30) }
    }

    suspend fun setPersonalityPreset(presetId: String) {
        context.settingsDataStore.edit { it[KEY_PERSONALITY_PRESET] = presetId }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_HAPTICS_ENABLED] = enabled }
    }

    suspend fun setAnimationsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_ANIMATIONS_ENABLED] = enabled }
    }

    suspend fun setAutoRead(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_READ] = enabled }
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_TTS_ENABLED] = enabled }
    }

    suspend fun setTtsRate(rate: Float) {
        context.settingsDataStore.edit { it[KEY_TTS_RATE] = rate.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setTtsPitch(pitch: Float) {
        context.settingsDataStore.edit { it[KEY_TTS_PITCH] = pitch.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setTtsVoice(voiceName: String) {
        context.settingsDataStore.edit { it[KEY_TTS_VOICE] = voiceName }
    }

    suspend fun setSttLanguage(language: String) {
        context.settingsDataStore.edit { it[KEY_STT_LANGUAGE] = language }
    }

    // ------------------------------------------------------- v0.8.1 features

    suspend fun setCallModeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_CALL_MODE_ENABLED] = enabled }
    }

    suspend fun setCallGreetingEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_CALL_GREETING_ENABLED] = enabled }
    }

    suspend fun setCallContinuousEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_CALL_CONTINUOUS_ENABLED] = enabled }
    }

    suspend fun setImagesEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_IMAGES_ENABLED] = enabled }
    }

    // ------------------------------------------------------------- v0.9 flags

    suspend fun setStudyModeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_STUDY_MODE] = enabled }
    }

    suspend fun setCaptureIntervalSec(seconds: Int) {
        context.settingsDataStore.edit { it[KEY_CAPTURE_INTERVAL] = seconds.coerceIn(2, 60) }
    }

    // ------------------------------------------------------------- v1.0 flags

    suspend fun setOnboardingCompleted(done: Boolean) {
        context.settingsDataStore.edit { it[KEY_ONBOARDING_DONE] = done }
    }

    suspend fun setDriveSyncEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_DRIVE_SYNC_ENABLED] = enabled }
    }

    suspend fun setDriveAccountEmail(email: String) {
        context.settingsDataStore.edit { it[KEY_DRIVE_ACCOUNT] = email.trim() }
    }

    /** One-time import of the old SharedPreferences-based settings (v"1.0"). */
    private suspend fun migrateLegacyPreferences() {
        val legacy = context.getSharedPreferences("chrisai_settings", Context.MODE_PRIVATE)
        val hasLegacy = legacy.contains("api_key") ||
            legacy.contains("model") ||
            legacy.contains("temperature") ||
            legacy.contains("models_cache")
        if (!hasLegacy) return

        val current = context.settingsDataStore.data.first()
        context.settingsDataStore.edit { prefs ->
            if (!current.contains(KEY_API_KEY)) {
                legacy.getString("api_key", null)?.let { prefs[KEY_API_KEY] = it }
            }
            if (!current.contains(KEY_MODEL)) {
                legacy.getString("model", null)?.let { prefs[KEY_MODEL] = it }
            }
            if (!current.contains(KEY_TEMPERATURE)) {
                legacy.getFloat("temperature", DEFAULT_TEMPERATURE.toFloat()).let { prefs[KEY_TEMPERATURE] = it }
            }
            if (!current.contains(KEY_MODELS_CACHE)) {
                legacy.getString("models_cache", null)?.let { prefs[KEY_MODELS_CACHE] = it }
            }
        }
        legacy.edit().clear().apply()
    }

    private fun decryptKey(stored: String): String {
        if (stored.isEmpty()) return ""
        if (stored.startsWith(PLAIN_PREFIX)) {
            return try {
                String(android.util.Base64.decode(stored.removePrefix(PLAIN_PREFIX), android.util.Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
        return try {
            NativeCrypto.decrypt(stored)
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseCachedModels(raw: String?): List<AiModel> {
        if (raw == null) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<AiModel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    AiModel(
                        id = o.getString("id"),
                        name = o.optString("name", o.optString("id")),
                        contextLength = o.optLong("context_length", 0),
                        promptPrice = o.optString("prompt_price")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeModels(models: List<AiModel>): String {
        val arr = JSONArray()
        models.forEach { model ->
            arr.put(
                JSONObject().apply {
                    put("id", model.id)
                    put("name", model.name)
                    put("context_length", model.contextLength)
                    put("prompt_price", model.promptPrice)
                }
            )
        }
        return arr.toString()
    }

    private companion object {
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("model")
        val KEY_TEMPERATURE = floatPreferencesKey("temperature")
        val KEY_MODELS_CACHE = stringPreferencesKey("models_cache")
        val KEY_PERSONALITY_NAME = stringPreferencesKey("personality_name")
        val KEY_PERSONALITY_PRESET = stringPreferencesKey("personality_preset")
        val KEY_PERSONALITY_HUMOR = intPreferencesKey("personality_humor")
        val KEY_PERSONALITY_DETAIL = intPreferencesKey("personality_detail")
        val KEY_PERSONALITY_STYLE = stringPreferencesKey("personality_style")
        val KEY_PERSONALITY_CUSTOM = stringPreferencesKey("personality_custom")
        val KEY_HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val KEY_ANIMATIONS_ENABLED = booleanPreferencesKey("animations_enabled")
        val KEY_AUTO_READ = booleanPreferencesKey("auto_read")
        val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val KEY_TTS_RATE = floatPreferencesKey("tts_rate")
        val KEY_TTS_PITCH = floatPreferencesKey("tts_pitch")
        val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        val KEY_STT_LANGUAGE = stringPreferencesKey("stt_language")
        val KEY_CALL_MODE_ENABLED = booleanPreferencesKey("call_mode_enabled")
        val KEY_CALL_GREETING_ENABLED = booleanPreferencesKey("call_greeting_enabled")
        val KEY_CALL_CONTINUOUS_ENABLED = booleanPreferencesKey("call_continuous_enabled")
        val KEY_IMAGES_ENABLED = booleanPreferencesKey("images_enabled")
        val KEY_STUDY_MODE = booleanPreferencesKey("study_mode")
        val KEY_CAPTURE_INTERVAL = intPreferencesKey("capture_interval")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_DRIVE_SYNC_ENABLED = booleanPreferencesKey("drive_sync_enabled")
        val KEY_DRIVE_ACCOUNT = stringPreferencesKey("drive_account_email")
        const val DEFAULT_TEMPERATURE = 0.7
        const val PLAIN_PREFIX = "plain:"
    }
}