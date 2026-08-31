package com.chrispixel.chrisai.data.drive

import android.content.Context
import com.chrispixel.chrisai.BuildConfig
import com.chrispixel.chrisai.data.SettingsRepository
import com.chrispixel.chrisai.data.local.ChatStore
import com.chrispixel.chrisai.data.local.MemoryStore
import com.chrispixel.chrisai.data.model.ChatMessage
import com.chrispixel.chrisai.data.model.ChatRole
import com.chrispixel.chrisai.data.model.ChatSession
import com.chrispixel.chrisai.data.model.SessionKind
import java.util.UUID

/**
 * Android [CloudFileStore]: bridges Room (conversations + memories) and
 * DataStore (settings) to the logical [SyncFile] tree. API keys are never
 * exported (settings snapshot is the non-secret [SettingsCloud] subset).
 */
class LocalSyncStore(
    context: Context,
    private val chatStore: ChatStore,
    private val memory: MemoryStore,
    private val settings: SettingsRepository
) : CloudFileStore {

    private val cloudPrefs = context.getSharedPreferences("chrisai_cloud", Context.MODE_PRIVATE)

    override suspend fun localFiles(): List<SyncFile> {
        val out = ArrayList<SyncFile>()

        val memoryItems = memory.list().map { it.toCloud() }
        out.add(SyncFile(CloudCodec.memoryPath(), CloudCodec.encodeMemory(memoryItems)))
        out.add(SyncFile(CloudCodec.memoryTxtPath(), CloudCodec.encodeMemoryTxt(memoryItems)))

        chatStore.list().forEach { session ->
            out.add(
                SyncFile(
                    CloudCodec.conversationPath(session.id),
                    CloudCodec.encodeConversation(session.toCloud())
                )
            )
        }

        out.add(SyncFile(CloudCodec.settingsPath(), CloudCodec.encodeSettings(settingsSnapshot())))
        out.add(SyncFile(CloudCodec.dataPath(deviceId()), CloudCodec.encodeDeviceManifest(deviceManifest())))

        return out
    }

    override suspend fun applyRemote(files: List<SyncFile>) {
        var remoteMemory: List<CloudMemoryItem>? = null
        for (file in files) {
            when {
                file.path == CloudCodec.memoryPath() -> remoteMemory = CloudCodec.decodeMemory(file.content)
                file.path.startsWith("${CloudCodec.CONVERSATIONS_DIR}/") ->
                    importConversation(CloudCodec.decodeConversation(file.content))
                file.path == CloudCodec.settingsPath() -> importSettings(CloudCodec.decodeSettings(file.content))
                else -> Unit // Data/ manifests are device-specific; never imported
            }
        }
        remoteMemory?.let { mergeMemories(it) }
    }

    override suspend fun deleteLocal(path: String) {
        val fileName = path.substringAfterLast('/')
        val id = fileName.removePrefix("conversation_").removeSuffix(".json")
        if (fileName.startsWith("conversation_") && id.isNotBlank() && id != "json") {
            chatStore.delete(id)
        }
    }

    // ---------------------------------------------------------------- private

    private fun settingsSnapshot(): SettingsCloud {
        val p = settings.personality.value
        return SettingsCloud(
            personalityName = p.name,
            presetId = p.presetId,
            humorLevel = p.humorLevel,
            detailLevel = p.detailLevel,
            communicationStyle = p.communicationStyle,
            customInstructions = p.customInstructions,
            model = settings.model.value,
            temperature = settings.temperature.value,
            ttsEnabled = settings.ttsEnabled.value,
            autoRead = settings.autoRead.value,
            ttsRate = settings.ttsRate.value,
            ttsPitch = settings.ttsPitch.value,
            ttsVoice = settings.ttsVoice.value,
            sttLanguage = settings.sttLanguage.value,
            hapticsEnabled = settings.hapticsEnabled.value,
            animationsEnabled = settings.animationsEnabled.value,
            callModeEnabled = settings.callModeEnabled.value,
            greetingEnabled = settings.callGreetingEnabled.value,
            continuousEnabled = settings.callContinuousEnabled.value,
            imagesEnabled = settings.imagesEnabled.value,
            studyModeEnabled = settings.studyModeEnabled.value,
            captureIntervalSec = settings.captureIntervalSec.value,
            defaultSessionKind = SessionKind.DEFAULT.id,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    private suspend fun importSettings(cloud: SettingsCloud) {
        if (cloud.personalityName.isNotBlank()) {
            val p = settings.personality.value
            settings.setPersonality(
                p.copy(
                    name = cloud.personalityName,
                    presetId = cloud.presetId,
                    humorLevel = cloud.humorLevel.coerceIn(1, 5),
                    detailLevel = cloud.detailLevel.coerceIn(1, 5),
                    communicationStyle = cloud.communicationStyle,
                    customInstructions = cloud.customInstructions
                )
            )
        }
        if (cloud.model.isNotBlank()) settings.setSelectedModel(cloud.model)
        settings.setTemperature(cloud.temperature.coerceIn(0.0, 1.0))
        settings.setTtsEnabled(cloud.ttsEnabled)
        settings.setAutoRead(cloud.autoRead)
        settings.setTtsRate(cloud.ttsRate.coerceIn(0.5f, 2.0f))
        settings.setTtsPitch(cloud.ttsPitch.coerceIn(0.5f, 2.0f))
        settings.setTtsVoice(cloud.ttsVoice)
        settings.setSttLanguage(cloud.sttLanguage)
        settings.setHapticsEnabled(cloud.hapticsEnabled)
        settings.setAnimationsEnabled(cloud.animationsEnabled)
        settings.setCallModeEnabled(cloud.callModeEnabled)
        settings.setCallGreetingEnabled(cloud.greetingEnabled)
        settings.setCallContinuousEnabled(cloud.continuousEnabled)
        settings.setImagesEnabled(cloud.imagesEnabled)
        settings.setStudyModeEnabled(cloud.studyModeEnabled)
        settings.setCaptureIntervalSec(cloud.captureIntervalSec)
    }

    private suspend fun mergeMemories(items: List<CloudMemoryItem>) {
        for (item in items) {
            val text = item.content.trim()
            if (text.isEmpty()) continue
            if (!memory.exists(text)) memory.add(text)
        }
    }

    private suspend fun importConversation(cloud: CloudConversation) {
        if (cloud.id.isBlank() || cloud.title.isBlank()) return
        val messages = cloud.messages.map { m ->
            ChatMessage(
                role = ChatRole.fromApi(m.role),
                content = m.content,
                timestamp = m.timestampMillis
            )
        }
        chatStore.upsert(
            ChatSession(
                id = cloud.id,
                title = cloud.title,
                createdAt = cloud.createdAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                updatedAt = cloud.updatedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                messages = messages,
                kind = SessionKind.fromId(cloud.kind)
            )
        )
    }

    private fun deviceId(): String {
        val existing = cloudPrefs.getString("device_id", null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = UUID.randomUUID().toString()
        cloudPrefs.edit().putString("device_id", fresh).apply()
        return fresh
    }

    private fun deviceManifest() = DeviceManifest(
        deviceId = deviceId(),
        appVersion = BuildConfig.VERSION_NAME,
        createdAtMillis = System.currentTimeMillis()
    )
}

// ------------------------------------------------- mapping helpers (pure, JVM)

/** Domain memory -> cloud item (local entity carries no category/importance). */
fun com.chrispixel.chrisai.data.model.Memory.toCloud(): CloudMemoryItem = CloudMemoryItem(
    id = id,
    content = text,
    createdAtMillis = createdAt,
    updatedAtMillis = updatedAt
)

internal fun ChatSession.toCloud(): CloudConversation = CloudConversation(
    id = id,
    title = title,
    createdAtMillis = createdAt,
    updatedAtMillis = updatedAt,
    kind = kind.id,
    messages = messages.map { m ->
        CloudMessage(
            role = m.role.apiValue,
            content = m.content,
            timestampMillis = m.timestamp
        )
    }
)