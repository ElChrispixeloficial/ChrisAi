package com.chrispixel.chrisai.data.tools.android

import android.content.Context
import com.chrispixel.chrisai.data.tools.Tool
import com.chrispixel.chrisai.data.tools.ToolRegistry
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds the [ToolRegistry] with all Android-backed [Tool]s.
 * Created once in the AppContainer; no logic lives in the UI layer.
 */
class AndroidToolsProvider(context: Context) {

    private val appContext = context.applicationContext
    private val counter = AtomicInteger()

    private val installedApps = InstalledAppsTool(appContext, counter)

    val registry: ToolRegistry = ToolRegistry(
        listOf(
            installedApps.searchTool(),
            installedApps.openTool()
        ) + SchedulerTools(appContext).tools() + SystemInfoTools(appContext).tools()
    )

    fun tools(): List<Tool> = registry.all()
    fun schemas(): String = registry.describe()
}