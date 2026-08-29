package com.chrispixel.chrisai.data.tools.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives alarm/timer firings scheduled via AlarmManager.
 * Declared in the manifest (exported=false); fires a notification and a
 * short vibration even if the app process is not alive.
 */
class ChrisSchedulerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL)
            ?: intent.getStringExtra(EXTRA_TYPE).orEmpty()
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "Temporizador"
        if (label.isBlank()) return

        val title = when (type) {
            TYPE_ALARM -> "⏰ Alarma"
            else -> "⏱️ Temporizador"
        }
        val message = if (label.startsWith("Temporizador")) label else "$type: $label"

        ToolNotifier.show(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, 2000), title, message)
    }

    companion object {
        const val EXTRA_TYPE = "chris.type"
        const val EXTRA_LABEL = "chris.label"
        const val EXTRA_NOTIFICATION_ID = "chris.notif_id"
        const val TYPE_TIMER = "timer"
        const val TYPE_ALARM = "alarm"
    }
}