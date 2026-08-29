package com.chrispixel.chrisai.data

import android.app.Application
import androidx.room.Room
import com.chrispixel.chrisai.data.haptics.Haptics
import com.chrispixel.chrisai.data.local.ChatStore
import com.chrispixel.chrisai.data.local.MemoryStore
import com.chrispixel.chrisai.data.local.db.AppDatabase
import com.chrispixel.chrisai.data.remote.OpenRouterApi
import com.chrispixel.chrisai.data.speech.SttController
import com.chrispixel.chrisai.data.speech.TtsController
import com.chrispixel.chrisai.data.tools.ToolRegistry
import com.chrispixel.chrisai.data.tools.android.AndroidToolsProvider
import com.chrispixel.chrisai.data.tools.android.ToolManager
import com.chrispixel.chrisai.data.update.UpdaterRepository
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
    ).build()

    val settings: SettingsRepository = SettingsRepository(application, appScope)
    val memory: MemoryStore = MemoryStore(database)
    val chatStore: ChatStore = ChatStore(database)

    val api: OpenRouterApi = OpenRouterApi()
    val updater: UpdaterRepository = UpdaterRepository(application)

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
        chatStore = chatStore,
        settings = settings,
        memory = memory,
        tools = toolManager
    )
}