package org.cygnusx1.openbu.notify

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.bambuddy.BambuddyClient
import kotlin.concurrent.thread

/**
 * Handles the buttons on print notifications (Pause, Resume, Power off) by calling the
 * Bambuddy server, then replaces the notification with a short result message.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val printerId = intent.getIntExtra(EXTRA_PRINTER_ID, -1)
        val plugId = intent.getIntExtra(EXTRA_PLUG_ID, -1)
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "BamMate"
        if (printerId < 0) return

        // Show that the tap registered while the request runs.
        showResult(app, notifId, title, "Working...", timeoutMs = 0)

        val pending = goAsync() // network calls can't run on the main thread; keep the receiver alive
        thread(name = "bammate-action") {
            val message = try {
                val prefs = NotifyPrefs.open(app) ?: throw IllegalStateException("Settings unavailable")
                val cfg = NotifyPrefs.bambuddyConfig(prefs) ?: throw IllegalStateException("No Bambuddy server set up")
                val client = BambuddyClient(cfg.url, cfg.apiKey)
                when (action) {
                    ACTION_PAUSE -> {
                        client.pause(printerId)
                        "Pause sent."
                    }
                    ACTION_CLEAR_PLATE -> {
                        client.clearPlate(printerId)
                        "Marked the plate as cleared."
                    }
                    ACTION_RESUME -> {
                        client.resume(printerId)
                        "Resume sent."
                    }
                    ACTION_POWER_OFF -> {
                        // Never cut power under a running print, even if the alert is old.
                        val s = client.status(printerId)
                        if (s.state in setOf("PREPARE", "SLICING", "RUNNING", "PAUSE")) {
                            "Not turned off: ${s.name} is printing again."
                        } else if (plugId < 0) {
                            "No smart plug for this printer."
                        } else {
                            client.controlPlug(plugId, "off")
                            "${s.name} powered off."
                        }
                    }
                    else -> "Unknown action."
                }
            } catch (e: Exception) {
                Log.w(TAG, "Action $action failed", e)
                "Failed: " + (e.message ?: e.javaClass.simpleName)
            }
            showResult(app, notifId, title, message, timeoutMs = 20_000)
            pending.finish()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showResult(context: Context, id: Int, title: String, text: String, timeoutMs: Long) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        NotifyChannels.ensure(context)
        val b = NotificationCompat.Builder(context, NotifyChannels.SERVICE)
            .setSmallIcon(R.drawable.ic_stat_printer)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        nm.notify(id, b.build())
    }

    companion object {
        private const val TAG = "BamMateAction"
        const val ACTION_PAUSE = "org.cygnusx1.openbu.action.PAUSE"
        const val ACTION_RESUME = "org.cygnusx1.openbu.action.RESUME"
        const val ACTION_POWER_OFF = "org.cygnusx1.openbu.action.POWER_OFF"
        const val ACTION_CLEAR_PLATE = "org.cygnusx1.openbu.action.CLEAR_PLATE"
        const val EXTRA_PRINTER_ID = "printer_id"
        const val EXTRA_PLUG_ID = "plug_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TITLE = "title"
    }
}
