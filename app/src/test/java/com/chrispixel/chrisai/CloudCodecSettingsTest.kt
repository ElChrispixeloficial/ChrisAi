package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.drive.CloudCodec
import com.chrispixel.chrisai.data.drive.CloudConversation
import com.chrispixel.chrisai.data.drive.CloudMessage
import com.chrispixel.chrisai.data.drive.DeviceManifest
import com.chrispixel.chrisai.data.drive.SettingsCloud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.0 cloud (de)serialization: settings, device manifest, conversation kind. */
class CloudCodecSettingsTest {

    @Test
    fun `settings round trip preserves every non-secret value`() {
        val original = SettingsCloud(
            personalityName = "Chris",
            presetId = "formal",
            humorLevel = 4,
            detailLevel = 3,
            communicationStyle = "directa",
            customInstructions = "Sé breve",
            model = "openrouter/free",
            temperature = 0.5,
            ttsEnabled = true,
            autoRead = true,
            ttsRate = 1.2f,
            ttsPitch = 0.8f,
            ttsVoice = "español",
            sttLanguage = "es-ES",
            hapticsEnabled = false,
            animationsEnabled = false,
            callModeEnabled = false,
            greetingEnabled = true,
            continuousEnabled = false,
            imagesEnabled = false,
            studyModeEnabled = true,
            captureIntervalSec = 10,
            defaultSessionKind = "study",
            updatedAtMillis = 1234L
        )
        val restored = CloudCodec.decodeSettings(CloudCodec.encodeSettings(original))
        assertEquals(original, restored)
    }

    @Test
    fun `settings json never contains credential material`() {
        val encoded = CloudCodec.encodeSettings(
            SettingsCloud(personalityName = "Chris", ttsRate = 1.1f)
        ).lowercase()
        assertFalse(encoded.contains("api"))
        assertFalse(encoded.contains("secret"))
        assertFalse(encoded.contains("key"))
        assertFalse(encoded.contains("token"))
        assertFalse(encoded.contains("oauth"))
    }

    @Test
    fun `decode of malformed or empty settings falls back to defaults`() {
        assertEquals(SettingsCloud(), CloudCodec.decodeSettings("nonsense"))
        assertEquals(SettingsCloud(), CloudCodec.decodeSettings(""))
    }

    @Test
    fun `device manifest round trip`() {
        val original = DeviceManifest("dev-1", "1.0.0", 42L)
        assertEquals(original, CloudCodec.decodeDeviceManifest(CloudCodec.encodeDeviceManifest(original)))
    }

    @Test
    fun `conversation kind survives the codec`() {
        val conversation = CloudConversation(
            id = "id1",
            title = "T",
            kind = "study",
            messages = listOf(CloudMessage(role = "user", content = "hola", timestampMillis = 1L))
        )
        val restored = CloudCodec.decodeConversation(CloudCodec.encodeConversation(conversation))
        assertEquals("study", restored.kind)
        assertEquals(1, restored.messages.size)
        assertEquals("hola", restored.messages.first().content)
    }

    @Test
    fun `legacy conversations without kind decode to general`() {
        // Hand-written pre-1.0 payload (no "kind" field on purpose).
        val legacy = """{"version":1,"id":"id2","title":"T2","messages":[]}"""
        val restored = CloudCodec.decodeConversation(legacy)
        assertEquals("general", restored.kind)
    }

    @Test
    fun `v1 layout paths use the requested folders`() {
        assertEquals("Memory/Memory.json", CloudCodec.memoryPath())
        assertEquals("Settings/Settings.json", CloudCodec.settingsPath())
        assertEquals("Data/dev-1.json", CloudCodec.dataPath("dev-1"))
        assertTrue(CloudCodec.conversationPath("ab-1").startsWith("Conversations/"))
        assertTrue(CloudCodec.conversationPath("ab-1").endsWith(".json"))
    }
}