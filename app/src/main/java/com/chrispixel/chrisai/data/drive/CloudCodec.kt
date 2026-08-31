package com.chrispixel.chrisai.data.drive

import org.json.JSONArray
import org.json.JSONObject
import com.chrispixel.chrisai.data.model.SessionKind

/**
 * JSON (de)serialization for the cloud snapshot (v0.8).
 *
 * Drive layout:
 *   ChrisAI/
 *     Memory.json        (structured memories + version)
 *     Memory.txt         (human-readable export, optional)
 *     Conversations/
 *       conversation_<id>.json
 *
 * Room remains the local source of truth; these JSON files are the remote sync
 * representation. Pure Kotlin + org.json so it is unit-testable on the JVM.
 */
object CloudCodec {

    const val LAYOUT_VERSION = 1
    const val MEMORY_FILE = "Memory.json"
    const val MEMORY_TXT = "Memory.txt"
    const val CONVERSATIONS_DIR = "Conversations"
    const val SETTINGS_DIR = "Settings"
    const val DATA_DIR = "Data"
    const val SETTINGS_FILE = "Settings.json"
    const val MEMORY_DIR = "Memory"

    // v1.0 logical paths under the ChrisAI root tree.
    fun memoryPath(): String = "$MEMORY_DIR/$MEMORY_FILE"
    fun memoryTxtPath(): String = "$MEMORY_DIR/$MEMORY_TXT"
    fun conversationPath(id: String): String = "$CONVERSATIONS_DIR/${conversationFileName(id)}"
    fun settingsPath(): String = "$SETTINGS_DIR/$SETTINGS_FILE"
    fun dataPath(deviceId: String): String = "$DATA_DIR/$deviceId.json"

    fun conversationFileName(id: String): String = "conversation_${sanitize(id)}.json"

    fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

    // ---------------------------------------------------------------- memory

    /** Memory.json document. */
    fun encodeMemory(memory: List<CloudMemoryItem>): String {
        val arr = JSONArray()
        memory.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("content", m.content)
                    .put("category", m.category)
                    .put("importance", m.importance)
                    .put("tags", JSONArray(m.tags))
                    .put("createdAt", m.createdAtMillis)
                    .put("updatedAt", m.updatedAtMillis)
            )
        }
        return JSONObject()
            .put("version", LAYOUT_VERSION)
            .put("memories", arr)
            .toString(2)
    }

    fun decodeMemory(json: String): List<CloudMemoryItem> {
        return try {
            val doc = JSONObject(json)
            val arr = doc.optJSONArray("memories") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val tags = o.optJSONArray("tags")
            CloudMemoryItem(
                id = o.optString("id"),
                content = o.optString("content"),
                category = o.optString("category", "otro"),
                importance = o.optInt("importance", 3).coerceIn(1, 5),
                tags = if (tags == null) emptyList() else (0 until tags.length()).mapNotNull { tags.optString(it) },
                createdAtMillis = o.optLong("createdAt", 0L),
                updatedAtMillis = o.optLong("updatedAt", 0L)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
    }

    /** Human-readable dump for [Memory.txt]. */
    fun encodeMemoryTxt(memory: List<CloudMemoryItem>): String {
        if (memory.isEmpty()) return "ChrisAI no tiene recuerdos guardados todavía.\n"
        val sb = StringBuilder("ChrisAI — recuerdos\n=====================\n\n")
        memory.sortedByDescending { it.updatedAtMillis }.forEachIndexed { index, m ->
            sb.append("${index + 1}. ")
                .append(m.content)
                .append("\n")
            if (m.tags.isNotEmpty()) sb.append("   Etiquetas: ").append(m.tags.joinToString(", ")).append("\n")
            sb.append("\n")
        }
        return sb.toString()
    }

    // ---------------------------------------------------------- conversations

    fun encodeConversation(conv: CloudConversation): String {
        val messages = JSONArray()
        conv.messages.forEach { m ->
            messages.put(
                JSONObject()
                    .put("role", m.role)
                    .put("content", m.content)
                    .put("timestamp", m.timestampMillis)
            )
        }
        return JSONObject()
            .put("version", LAYOUT_VERSION)
            .put("id", conv.id)
            .put("title", conv.title)
            .put("createdAt", conv.createdAtMillis)
            .put("updatedAt", conv.updatedAtMillis)
            .put("kind", conv.kind)
            .put("messages", messages)
            .toString(2)
    }

    fun decodeConversation(json: String): CloudConversation = try {
        val doc = JSONObject(json)
        val arr = doc.optJSONArray("messages")
        CloudConversation(
            id = doc.optString("id"),
            title = doc.optString("title"),
            createdAtMillis = doc.optLong("createdAt", 0L),
            updatedAtMillis = doc.optLong("updatedAt", 0L),
            kind = doc.optString("kind", SessionKind.DEFAULT.id),
            messages = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CloudMessage(
                    role = o.optString("role"),
                    content = o.optString("content"),
                    timestampMillis = o.optLong("timestamp", 0L)
                )
            }
        )
    } catch (e: Exception) {
        CloudConversation(id = "", title = "")
    }

    /** Merge of two memory lists (conflict MERGE): newest per id wins, dedup by id. */
    fun mergeMemory(local: List<CloudMemoryItem>, remote: List<CloudMemoryItem>): List<CloudMemoryItem> {
        val byId = HashMap<String, CloudMemoryItem>(local.size + remote.size)
        (local + remote).forEach { item -> byId[item.id] = item }
        return byId.values.sortedByDescending { it.updatedAtMillis }
    }

    // ---------------------------------------------------------- v1.0 settings

    /** Non-secret settings snapshot for Settings.json (never contains keys). */
    fun encodeSettings(settings: SettingsCloud): String = JSONObject()
        .put("version", LAYOUT_VERSION)
        .put("personalityName", settings.personalityName)
        .put("presetId", settings.presetId)
        .put("humorLevel", settings.humorLevel)
        .put("detailLevel", settings.detailLevel)
        .put("communicationStyle", settings.communicationStyle)
        .put("customInstructions", settings.customInstructions)
        .put("model", settings.model)
        .put("temperature", settings.temperature)
        .put("ttsEnabled", settings.ttsEnabled)
        .put("autoRead", settings.autoRead)
        .put("ttsRate", settings.ttsRate.toDouble())
        .put("ttsPitch", settings.ttsPitch.toDouble())
        .put("ttsVoice", settings.ttsVoice)
        .put("sttLanguage", settings.sttLanguage)
        .put("hapticsEnabled", settings.hapticsEnabled)
        .put("animationsEnabled", settings.animationsEnabled)
        .put("callModeEnabled", settings.callModeEnabled)
        .put("greetingEnabled", settings.greetingEnabled)
        .put("continuousEnabled", settings.continuousEnabled)
        .put("imagesEnabled", settings.imagesEnabled)
        .put("studyModeEnabled", settings.studyModeEnabled)
        .put("captureIntervalSec", settings.captureIntervalSec)
        .put("defaultSessionKind", settings.defaultSessionKind)
        .put("updatedAt", settings.updatedAtMillis)
        .toString(2)

    fun decodeSettings(json: String): SettingsCloud = try {
        val doc = JSONObject(json)
        SettingsCloud(
            personalityName = doc.optString("personalityName"),
            presetId = doc.optString("presetId"),
            humorLevel = doc.optInt("humorLevel", 2),
            detailLevel = doc.optInt("detailLevel", 2),
            communicationStyle = doc.optString("communicationStyle"),
            customInstructions = doc.optString("customInstructions"),
            model = doc.optString("model"),
            temperature = doc.optDouble("temperature", 0.7),
            ttsEnabled = doc.optBoolean("ttsEnabled", false),
            autoRead = doc.optBoolean("autoRead", false),
            ttsRate = doc.optDouble("ttsRate", 1.0).toFloat(),
            ttsPitch = doc.optDouble("ttsPitch", 1.0).toFloat(),
            ttsVoice = doc.optString("ttsVoice"),
            sttLanguage = doc.optString("sttLanguage"),
            hapticsEnabled = doc.optBoolean("hapticsEnabled", true),
            animationsEnabled = doc.optBoolean("animationsEnabled", true),
            callModeEnabled = doc.optBoolean("callModeEnabled", true),
            greetingEnabled = doc.optBoolean("greetingEnabled", true),
            continuousEnabled = doc.optBoolean("continuousEnabled", true),
            imagesEnabled = doc.optBoolean("imagesEnabled", true),
            studyModeEnabled = doc.optBoolean("studyModeEnabled", false),
            captureIntervalSec = doc.optInt("captureIntervalSec", 5),
            defaultSessionKind = doc.optString("defaultSessionKind", SessionKind.DEFAULT.id),
            updatedAtMillis = doc.optLong("updatedAt", 0L)
        )
    } catch (e: Exception) {
        SettingsCloud()
    }

    // -------------------------------------------------------- v1.0 device data

    fun encodeDeviceManifest(manifest: DeviceManifest): String = JSONObject()
        .put("version", LAYOUT_VERSION)
        .put("deviceId", manifest.deviceId)
        .put("appVersion", manifest.appVersion)
        .put("createdAt", manifest.createdAtMillis)
        .toString(2)

    fun decodeDeviceManifest(json: String): DeviceManifest = try {
        val doc = JSONObject(json)
        DeviceManifest(
            deviceId = doc.optString("deviceId"),
            appVersion = doc.optString("appVersion"),
            createdAtMillis = doc.optLong("createdAt", 0L)
        )
    } catch (e: Exception) {
        DeviceManifest()
    }
}

/** Non-secret, syncable settings snapshot (v1.0). Never contains API keys. */
data class SettingsCloud(
    val personalityName: String = "",
    val presetId: String = "casual",
    val humorLevel: Int = 2,
    val detailLevel: Int = 2,
    val communicationStyle: String = "",
    val customInstructions: String = "",
    val model: String = "",
    val temperature: Double = 0.7,
    val ttsEnabled: Boolean = false,
    val autoRead: Boolean = false,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsVoice: String = "",
    val sttLanguage: String = "",
    val hapticsEnabled: Boolean = true,
    val animationsEnabled: Boolean = true,
    val callModeEnabled: Boolean = true,
    val greetingEnabled: Boolean = true,
    val continuousEnabled: Boolean = true,
    val imagesEnabled: Boolean = true,
    val studyModeEnabled: Boolean = false,
    val captureIntervalSec: Int = 5,
    val defaultSessionKind: String = SessionKind.DEFAULT.id,
    val updatedAtMillis: Long = 0L
)

/** Device identity manifest for the Data/ folder (v1.0). */
data class DeviceManifest(
    val deviceId: String = "",
    val appVersion: String = "",
    val createdAtMillis: Long = 0L
)