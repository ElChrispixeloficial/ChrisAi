package com.chrispixel.chrisai.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chrispixel.chrisai.BuildConfig
import com.chrispixel.chrisai.data.model.AiModel
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

    init {
        scope.launch { migrateLegacyPreferences() }
    }

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
        const val DEFAULT_TEMPERATURE = 0.7
        const val PLAIN_PREFIX = "plain:"
    }
}