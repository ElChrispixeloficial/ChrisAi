package com.chrispixel.chrisai.data.tools.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.chrispixel.chrisai.data.tools.Tool
import com.chrispixel.chrisai.data.tools.ToolParam
import com.chrispixel.chrisai.data.tools.ToolResult
import com.chrispixel.chrisai.data.tools.ToolResultStatus
import com.chrispixel.chrisai.data.tools.ToolRiskLevel
import java.util.concurrent.atomic.AtomicInteger

/**
 * ChrisTools: search_installed_apps + open_app.
 * No hardcoded list: packages are queried at runtime via PackageManager
 * and matched with [AppMatcher] (partial, case-insensitive).
 */
class InstalledAppsTool(private val context: Context, private val counter: AtomicInteger) {

    companion object {
        const val LIMIT = 8
    }

    private fun installedApps(): List<AppMatcher.App> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(query, PackageManager.MATCH_ALL)
        if (resolved.isNotEmpty()) {
            return resolved.mapNotNull { info ->
                val label = info.loadLabel(pm)?.toString()
                if (label.isNullOrBlank()) null
                else AppMatcher.App(label, info.activityInfo.packageName)
            }
        }
        val fallback = pm.getInstalledApplications(0)
        return fallback.mapNotNull { appInfo ->
            val label = appInfo.loadLabel(pm)?.toString()
            if (label.isNullOrBlank()) null
            else AppMatcher.App(label, appInfo.packageName)
        }
    }

    private fun search(query: String, limit: Int = LIMIT): List<AppMatcher.App> =
        AppMatcher.search(installedApps(), query, limit)

    fun searchTool(): Tool = object : Tool {
        override val id = "search_installed_apps"
        override val name = "Buscar apps instaladas"
        override val description =
            "Busca aplicaciones instaladas por nombre o paquete (coincidencias parciales) y las lista."
        override val parameters = listOf(
            ToolParam("query", "string", "Texto a buscar (puede ser parcial)", required = true)
        )
        override val permissions = emptyList<String>()
        override val risk = ToolRiskLevel.SAFE
        override val requiresConfirmation = false
        override val requiresShizuku = false
        override suspend fun execute(args: Map<String, String>): ToolResult {
            val query = args["query"].orEmpty()
            val matches = search(query, limit = 5)
            if (matches.isEmpty()) {
                return ToolResult(
                    ToolResultStatus.NOT_FOUND, id,
                    "No encontré ninguna aplicación instalada que coincida."
                )
            }
            val list = matches.joinToString("\n") { "- ${it.label} (${it.packageName})" }
            return ToolResult(
                ToolResultStatus.SUCCESS, id,
                "Encontré estas aplicaciones:\n$list",
                data = mapOf("results" to list, "count" to matches.size.toString())
            )
        }
    }

    fun openTool(): Tool = object : Tool {
        override val id = "open_app"
        override val name = "Abrir aplicación"
        override val description =
            "Abre la aplicación indicada por nombre. Si hay varias coincidencias, elige la más clara."
        override val parameters = listOf(
            ToolParam("appName", "string", "Nombre (o texto parcial) de la aplicación", required = true)
        )
        override val permissions = emptyList<String>()
        override val risk = ToolRiskLevel.SAFE
        override val requiresConfirmation = false
        override val requiresShizuku = false
        override suspend fun execute(args: Map<String, String>): ToolResult {
            val appName = args["appName"].orEmpty().trim()
            if (appName.isBlank()) {
                return ToolResult(ToolResultStatus.FAILED, id, "No indicaste qué aplicación abrir.")
            }
            val explicitPackage = args["packageName"].orEmpty().trim()
            val target = when {
                explicitPackage.isNotBlank() ->
                    installedApps().firstOrNull { it.packageName == explicitPackage }
                else -> search(appName, limit = 2).firstOrNull()
            }
            if (target == null) {
                return ToolResult(
                    ToolResultStatus.NOT_FOUND, id,
                    "⚠️ No encontré esa aplicación instalada. Tienes alguna con otro nombre?"
                )
            }
            return try {
                val launch = context.packageManager.getLaunchIntentForPackage(target.packageName)
                    ?: throw ActivityNotFoundException()
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                ToolResult(
                    ToolResultStatus.SUCCESS, id,
                    "✓ ${target.label} abierto.",
                    data = mapOf("app" to target.label, "package" to target.packageName)
                )
            } catch (e: ActivityNotFoundException) {
                ToolResult(
                    ToolResultStatus.NO_COMPATIBLE_APP, id,
                    "No pude abrir ${target.label}: no hay una actividad compatible."
                )
            } catch (e: SecurityException) {
                ToolResult(ToolResultStatus.PERMISSION_DENIED, id, "Sin permiso para abrir la aplicación.")
            }
        }
    }
}