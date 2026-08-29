package com.chrispixel.chrisai.data.tools.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.chrispixel.chrisai.data.tools.Tool
import com.chrispixel.chrisai.data.tools.ToolCall
import com.chrispixel.chrisai.data.tools.ToolParam
import com.chrispixel.chrisai.data.tools.ToolRegistry
import com.chrispixel.chrisai.data.tools.ToolResult
import com.chrispixel.chrisai.data.tools.ToolResultStatus
import com.chrispixel.chrisai.data.tools.ToolRiskLevel
import java.util.Calendar

/**
 * ChrisTools: create_timer, cancel_timer, create_alarm, cancel_alarm.
 * Uses AlarmManager (inexact-by-design, no special permission) so they
 * survive the app being closed; the fired action lands in a notification.
 */
class SchedulerTools(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(
        requestType: String,
        key: String,
        label: String,
        notificationId: Int
    ): PendingIntent {
        val intent = Intent(context, ChrisSchedulerReceiver::class.java)
            .setAction("com.chrispixel.chrisai.action." + requestType)
            .putExtra(ChrisSchedulerReceiver.EXTRA_TYPE, requestType)
            .putExtra(ChrisSchedulerReceiver.EXTRA_LABEL, label)
            .putExtra(ChrisSchedulerReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra("chris.key", key)
        return PendingIntent.getBroadcast(
            context,
            key.hashCode() and 0x7fffffff,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestKey(toolId: String, args: Map<String, String>): String =
        toolId + args.entries.sortedBy { it.key }.joinToString("") { "${it.key}=${it.value}" }

    fun createTimer(args: Map<String, String>): ToolResult {
        val seconds = args["durationSeconds"]?.toIntOrNull()
        if (seconds == null || seconds <= 0 || seconds > 86400) {
            return ToolResult(
                ToolResultStatus.FAILED, "create_timer",
                "No pude crear el temporizador: duración no válida."
            )
        }
        val minutes = seconds / 60
        val label = "Temporizador de $minutes min"
        val key = requestKey("create_timer", args)
        val triggerAt = System.currentTimeMillis() + seconds * 1000L
        val pi = pendingIntent(ChrisSchedulerReceiver.TYPE_TIMER, key, label, notificationIdForKey(key))
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        return ToolResult(
            ToolResultStatus.SUCCESS, "create_timer",
            "⏱️ Temporizador creado: sonará en $minutes min."
        )
    }

    fun cancelTimer(args: Map<String, String>): ToolResult {
        val key = requestKey("create_timer", args)
        val pi = pendingIntent(
            ChrisSchedulerReceiver.TYPE_TIMER, key,
            "Temporizador", notificationIdForKey(key)
        )
        alarmManager.cancel(pi)
        return ToolResult(ToolResultStatus.SUCCESS, "cancel_timer", "⏱️ Temporizador cancelado.")
    }

    fun createAlarm(args: Map<String, String>): ToolResult {
        val hours = args["hours"]?.toIntOrNull()
        val minutes = args["minutes"]?.toIntOrNull() ?: 0
        if (hours == null || hours !in 0..23 || minutes !in 0..59) {
            return ToolResult(
                ToolResultStatus.FAILED, "create_alarm",
                "No pude crear la alarma: hora no válida."
            )
        }
        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hours)
            set(Calendar.MINUTE, minutes)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val label = "Alarma a las %02d:%02d".format(hours, minutes)
        val key = requestKey("create_alarm", args)
        val pi = pendingIntent(ChrisSchedulerReceiver.TYPE_ALARM, key, label, notificationIdForKey(key))
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
        return ToolResult(
            ToolResultStatus.SUCCESS, "create_alarm",
            "⏰ Alarma creada para las %02d:%02d.".format(hours, minutes)
        )
    }

    fun cancelAlarm(args: Map<String, String>): ToolResult {
        val key = requestKey("create_alarm", args)
        val pi = pendingIntent(
            ChrisSchedulerReceiver.TYPE_ALARM, key,
            "Alarma", notificationIdForKey(key)
        )
        alarmManager.cancel(pi)
        return ToolResult(ToolResultStatus.SUCCESS, "cancel_alarm", "⏰ Alarma cancelada.")
    }

    private fun notificationIdForKey(key: String): Int =
        2000 + ((key.hashCode() and 0x7fffffff) % 9000)

    fun tools(): List<Tool> = listOf(
        object : Tool {
            override val id = "create_timer"
            override val name = "Crear temporizador"
            override val description = "Crea un temporizador que avisa al terminar (duranción en segundos)."
            override val parameters = listOf(
                ToolParam("durationSeconds", "integer", "Duración en segundos", required = true)
            )
            override val permissions = listOf("POST_NOTIFICATIONS")
            override val risk = ToolRiskLevel.SAFE
            override val requiresConfirmation = false
            override val requiresShizuku = false
            override suspend fun execute(args: Map<String, String>) = createTimer(args)
        },
        object : Tool {
            override val id = "cancel_timer"
            override val name = "Cancelar temporizador"
            override val description = "Cancela un temporizador creado (misma duración en segundos)."
            override val parameters = listOf(
                ToolParam("durationSeconds", "integer", "Duración en segundos", required = true)
            )
            override val permissions = emptyList<String>()
            override val risk = ToolRiskLevel.SAFE
            override val requiresConfirmation = false
            override val requiresShizuku = false
            override suspend fun execute(args: Map<String, String>) = cancelTimer(args)
        },
        object : Tool {
            override val id = "create_alarm"
            override val name = "Crear alarma"
            override val description = "Crea una alarma (hora y minutos en formato 24h)."
            override val parameters = listOf(
                ToolParam("hours", "integer", "Hora (0-23)", required = true),
                ToolParam("minutes", "integer", "Minutos (0-59)", required = false)
            )
            override val permissions = listOf("POST_NOTIFICATIONS")
            override val risk = ToolRiskLevel.SAFE
            override val requiresConfirmation = false
            override val requiresShizuku = false
            override suspend fun execute(args: Map<String, String>) = createAlarm(args)
        },
        object : Tool {
            override val id = "cancel_alarm"
            override val name = "Cancelar alarma"
            override val description = "Cancela una alarma creada (misma hora y minutos)."
            override val parameters = listOf(
                ToolParam("hours", "integer", "Hora (0-23)", required = true),
                ToolParam("minutes", "integer", "Minutos (0-59)", required = false)
            )
            override val permissions = emptyList<String>()
            override val risk = ToolRiskLevel.SAFE
            override val requiresConfirmation = false
            override val requiresShizuku = false
            override suspend fun execute(args: Map<String, String>) = cancelAlarm(args)
        }
    )
}