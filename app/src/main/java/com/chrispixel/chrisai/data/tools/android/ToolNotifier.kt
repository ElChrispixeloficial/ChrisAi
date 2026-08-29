package com.chrispixel.chrisai.data.tools.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.chrispixel.chrisai.R

/** Shared notification plumbing for ChrisTools (timers, alarms, notifications). */
internal object ToolNotifier {

    const val CHANNEL_ID = "chrisai_actions"
    private const val FEEDBACK_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.getNotificationChannel(CHANNEL_ID) ?: run {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ChrisAI acciones",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Avisos de temporizadores, alarmas y notificaciones ejecutadas por ChrisAI"
                }
            )
        }
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled()
    }

    /** Returns false when POST_NOTIFICATIONS is missing on API 33+. */
    fun show(context: Context, id: Int, title: String, message: String): Boolean {
        if (!canNotify(context)) return false
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        manager.notify(id, builder.build())
        return true
    }

    /** Shows a subtle "action done" feedback used by confirmations. */
    fun showFeedback(context: Context, text: String) {
        show(context, FEEDBACK_ID, "ChrisAI", text)
    }
}