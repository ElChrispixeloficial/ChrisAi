package com.chrispixel.chrisai.data

import android.app.Application
import androidx.room.Room
import com.chrispixel.chrisai.BuildConfig
import com.chrispixel.chrisai.data.haptics.Haptics
import com.chrispixel.chrisai.data.local.ChatStore
import com.chrispixel.chrisai.data.local.MemoryStore
import com.chrispixel.chrisai.data.local.db.AppDatabase
import com.chrispixel.chrisai.data.provider.GeminiProvider
import com.chrispixel.chrisai.data.provider.OpenRouterProvider
import com.chrispixel.chrisai.data.provider.ProviderEngine
import com.chrispixel.chrisai.data.remote.OpenRouterApi
import com.chrispixel.chrisai.data.speech.SttController
import com.chrispixel.chrisai.data.speech.TtsController
import com.chrispixel.chrisai.data.tools.ToolRegistry
import com.chrispixel.chrisai.data.tools.android.AndroidToolsProvider
import com.chrispixel.chrisai.data.tools.android.ToolManager
import com.chrispixel.chrisai.data.update.UpdaterRepository
import com.chrispixel.chrisai.data.vision.VisionMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual DI container shared across the app. */
class AppContainer(application: Application) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "chrisai.db"
    ).addMigrations(AppDatabase.MIGRATION_1_2).build()

    val settings: SettingsRepository = SettingsRepository(application, appScope)
    val memory: MemoryStore = MemoryStore(database)
    val chatStore: ChatStore = ChatStore(database)

    val api: OpenRouterApi = OpenRouterApi()
    val updater: UpdaterRepository = UpdaterRepository(application)

    // v0.9: live context sources (latest vision description + study flag) shared
    // with the ChatRepository's Context Engine.
    val contextSource = ChrisContextSource()
    // v0.9: billed periodic camera capture (controlled frames, no video stream).
    val camera: com.chrispixel.chrisai.data.vision.CameraCaptureSession =
        com.chrispixel.chrisai.data.vision.CameraCaptureSession(application)

    // v0.9 provider engine: OpenRouter primary, Gemini as the vision/error fallback.
    val providerEngine: ProviderEngine = ProviderEngine(
        primary = OpenRouterProvider(api) { settings.apiKey.value },
        fallback = GeminiProvider(
            api = com.chrispixel.chrisai.data.provider.GeminiApi(),
            apiKey = BuildConfig.GEMINI_API_KEY
        ),
        fallbackKey = BuildConfig.GEMINI_API_KEY,
        visionClassifier = com.chrispixel.chrisai.data.provider.VisionClassifier { model ->
            VisionMessage.support(model)
        }
    )

    // v0.6 sensory services (voice out, voice in, subtle haptics).
    val tts: TtsController = TtsController(application)
    val stt: SttController = SttController(application)
    val haptics: Haptics = Haptics(application, settings)

    // v0.7 ChrisTools: structured, safe tool execution.
    private val toolsProvider = AndroidToolsProvider(application)
    val tools: ToolRegistry = toolsProvider.registry
    val toolManager: ToolManager = ToolManager(tools)

    val chatRepository: ChatRepository = ChatRepository(
        api = api,
        engine = providerEngine,
        chatStore = chatStore,
        settings = settings,
        memory = memory,
        tools = toolManager,
        // The Context Engine reads these live values (vision capture + study flag).
        visionAnalysis = { contextSource.visionAnalysis },
        foregroundApp = { null },
        studyActive = { contextSource.studyActive }
    )
}

/** Mutable holder for v0.9 context inputs written by the ViewModel at runtime. */
class ChrisContextSource {
    @Volatile var visionAnalysis: String? = null
    @Volatile var studyActive: Boolean = false
}