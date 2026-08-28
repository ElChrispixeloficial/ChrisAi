package com.chrispixel.chrisai.data

import android.app.Application
import androidx.room.Room
import com.chrispixel.chrisai.data.local.ChatStore
import com.chrispixel.chrisai.data.local.MemoryStore
import com.chrispixel.chrisai.data.local.db.AppDatabase
import com.chrispixel.chrisai.data.remote.OpenRouterApi
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

    val chatRepository: ChatRepository = ChatRepository(
        api = api,
        chatStore = chatStore,
        settings = settings,
        memory = memory
    )
}