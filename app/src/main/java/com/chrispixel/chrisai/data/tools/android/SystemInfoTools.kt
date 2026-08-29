package com.chrispixel.chrisai.data.tools.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import com.chrispixel.chrisai.data.tools.Tool
import com.chrispixel.chrisai.data.tools.ToolParam
import com.chrispixel.chrisai.data.tools.ToolResult
import com.chrispixel.chrisai.data.tools.ToolResultStatus
import com.chrispixel.chrisai.data.tools.ToolRiskLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ChrisTools: open_url, search_play_store, show_notification, get_time,
 * get_battery_status, get_device_info.
 */
class SystemInfoTools(private val context: Context) {

    private fun openUrlTool(): Tool = object : Tool {
        override val id = "open_url"
        override val name = "Abrir URL"
        override val description = "Abre una URL en el navegador u otra aplicación compatible."
        override val parameters = listOf(
            ToolParam("url", "string", "Dirección web completa (https://...)", required = true)
        )
        override val permissions = emptyList<String>()
        override val risk = ToolRiskLevel.SAFE
        override val requiresConfirmation = false
        override val requiresShizuku = false
        override suspend fun execute(args: Map<String, String>): ToolResult {
            val raw = args["url"].orEmpty().trim()
            if (raw.isBlank()) {
                return ToolResult(ToolResultStatus.FAILED, id, "No indicaste ninguna URL.")
            }
            var url = raw
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            val uri = try {
                Uri.parse(url)
            } catch (_: Exception) {
                return ToolResult(ToolResultStatus.FAILED, id, "La URL no es válida.")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                ToolResult(ToolResultStatus.SUCCESS, id, "✓ Abriendo la página web.")
            } catch (e: ActivityNotFoundException) {
                ToolResult(ToolResultStatus.NO_COMPATIBLE_APP, id, "No hay navegador disponible.")
            }
        }
    }

    private fun searchPlayStoreTool(): Tool = object : Tool {
        override val id = "search_play_store"
        override val name = "Buscar en Play Store"
        override val description =
            "Abre la búsqueda de Google Play con el término indicado, o instala la app si se da el nombre exacto."
        override val parameters = listOf(
            ToolParam("query", "string", "Término de búsqueda o nombre de la app", required = true)
        )
        override val permissions = emptyList<String>()
        override val risk = ToolRiskLevel.SAFE
        override val requiresConfirmation = false
        override val requiresShizuku = false
        override suspend fun execute(args: Map<String, String>): ToolResult {
            val query = args["query"].orEmpty().trim()
            if (query.isBlank()) {
                return ToolResult(ToolResultStatus.FAILED, id, "No indicaste qué buscar.")
            }
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(query)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (try { context.startActivity(market); true } catch (e: ActivityNotFoundException) { false }) {
                return ToolResult(ToolResultStatus.SUCCESS, id, "✓ Abriendo Play Store.")
            }
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/search?q=${Uri.encode(query)}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(web)
                ToolResult(ToolResultStatus.SUCCESS, id, "✓ Abriendo la búsqueda en Play Store web.")
            } catch (e: ActivityNotFoundException) {
                ToolResult(
                    ToolResultStatus.NO_COMPATIBLE_APP, id,
                    "No encuentro una app para buscar en Google Play."
                )
            }
        }
    }

    private fun showNotificationTool(): Tool = object : Tool {
        override val id = "show_notification"
        override val name = "Mostrar notificación"
        override val description = "Muestra una notificación con el título y mensaje indicados."
        override val parameters = listOf(
            ToolParam("title", "string", "Título corto", required = true),
            ToolParam("message", "string", "Mensaje", required = true)
        )
        override val permissions = listOf("POST_NOTIFICATIONS")
        override val risk = ToolRiskLevel.SAFE
        override val requiresConfirmation = false
        override val requiresShizuku = false
        override suspend fun execute(args: Map<String, String>): ToolResult {
            val title = args["title"].orEmpty().trim().ifBlank { "ChrisAI" }
            val message = args["message"].orEmpty().trim()
            if (message.isBlank()) {
                return ToolResult(ToolResultStatus.FAILED, id, "La notificación no tiene mensaje.")
            }
            return if (ToolNotifier.show(context, 3001, title, message)) {
                ToolResult(ToolResultStatus.SUCCESS, id, "✓ Notificación mostrada.")
            } else {
                ToolResult(
                    ToolResultStatus.PERMISSION_DENIED, id,
                    "No tengo permiso para mostrar notificaciones en este dispositivo."
                )
            }
        }
    }

    private fun getTimeTool(): Tool = object : Tool {
        override val id = "get_time"
        override val name = "Consulta la hora"
        override val description = "Devuelve la hora y fecha actuales del dispositivo."
        override val parameters = emptyList<ToolParam>()
        override val permissions = emptyList<String>()
        override val risk = ToolRiskLevel.SAFE
        override val requiresConfirmation = false
        override val requiresShizuku = false
        override suspend fun execute(args: Map<String, String>): ToolResult {
            val now = Date()
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            val date = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", Locale("es", Locale.getDefault().language)).format(now)
            return ToolResult(
                ToolResultStatus.SUCCESS, id,
                "Son las $time.",
                data = mapOf("time" to time, "date" to date)
            )
        }
    }

    private fun getBatteryStatusTool(): Tool = object : Tool {
        override val id = "get_battery_status"
        override val name = "Consulta la batería"
        override val description = "Devuelve el nivel de batería y si está cargando."
        override val parameters = emptyList<ToolParam>()
        override val permissions = emptyList<String>()
        override val risk = ToolRiskLevel.SAFE
        override val requiresConfirmation = false
        override val requiresShizuku = false
        override suspend fun execute(args: Map<String, String>): ToolResult {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return ToolResult(ToolResultStatus.SUCCESS, id, "No pude leer la batería.")
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return if (percent >= 0) {
                val statusText = if (charging) "cargando" else "no está cargando"
                ToolResult(
                    ToolResultStatus.SUCCESS, id,
                    "La batería está al $percent% y $statusText.",
                    data = mapOf("percent" to percent.toString(), "charging" to charging.toString())
                )
            } else {
                ToolResult(ToolResultStatus.FAILED, id, "No pude leer el nivel de batería.")
            }
        }
    }

    private fun getDeviceInfoTool(): Tool = object : Tool {
        override val id = "get_device_info"
        override val name = "Información del dispositivo"
        override val description = "Devuelve marca, modelo y versión de Android del dispositivo."
        override val parameters = emptyList<ToolParam>()
        override val permissions = emptyList<String>()
        override val risk = ToolRiskLevel.SAFE
        override val requiresConfirmation = false
        override val requiresShizuku = false
        override suspend fun execute(args: Map<String, String>): ToolResult {
            val manufacturer = Build.MANUFACTURER ?: "Desconocido"
            val model = Build.MODEL ?: "Desconocido"
            val api = Build.VERSION.SDK_INT
            val release = Build.VERSION.RELEASE ?: "?"
            return ToolResult(
                ToolResultStatus.SUCCESS, id,
                "Dispositivo: $manufacturer $model · Android $release (API $api).",
                data = mapOf(
                    "manufacturer" to manufacturer,
                    "model" to model,
                    "androidRelease" to release,
                    "apiLevel" to api.toString()
                )
            )
        }
    }

    fun tools(): List<Tool> = listOf(
        openUrlTool(),
        searchPlayStoreTool(),
        showNotificationTool(),
        getTimeTool(),
        getBatteryStatusTool(),
        getDeviceInfoTool()
    )
}