package org.cygnusx1.openbu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.bambuddy.BambuddyClient
import org.cygnusx1.openbu.bambuddy.BbStatus
import org.cygnusx1.openbu.notify.NotifyPrefs
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Home-screen widget: each printer's state, time left and progress.
 *
 * It refreshes when the notification service polls Bambuddy (every ~15 s while alerts are on),
 * when the refresh button is tapped, and every 30 minutes on its own.
 */
class PrinterWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        fetchAndRender(context.applicationContext)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) fetchAndRender(context.applicationContext)
    }

    private fun fetchAndRender(app: Context) {
        val pending = goAsync() // network calls can't run on the main thread
        thread(name = "bammate-widget") {
            try {
                val prefs = NotifyPrefs.open(app)
                val cfg = prefs?.let { NotifyPrefs.bambuddyConfig(it) }
                if (cfg == null) {
                    renderMessage(app, "Open BamMate and set up your Bambuddy server.")
                } else {
                    val client = BambuddyClient(cfg.url, cfg.apiKey)
                    val statuses = client.listPrinters().mapNotNull { p ->
                        try {
                            client.status(p.id)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    render(app, statuses)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Widget refresh failed", e)
                renderMessage(app, "Can't reach Bambuddy. Away from home? Turn on your VPN.")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BamMateWidget"
        const val ACTION_REFRESH = "org.cygnusx1.openbu.widget.REFRESH"

        private val ROWS = listOf(
            Row(R.id.row1, R.id.row1_dot, R.id.row1_name, R.id.row1_status, R.id.row1_pct, R.id.row1_progress),
            Row(R.id.row2, R.id.row2_dot, R.id.row2_name, R.id.row2_status, R.id.row2_pct, R.id.row2_progress),
            Row(R.id.row3, R.id.row3_dot, R.id.row3_name, R.id.row3_status, R.id.row3_pct, R.id.row3_progress),
        )

        private data class Row(val root: Int, val dot: Int, val name: Int, val status: Int, val pct: Int, val progress: Int)

        private fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, PrinterWidget::class.java))

        /** Cheap check so the service only builds views when a widget is actually placed. */
        fun hasWidgets(context: Context): Boolean = widgetIds(context).isNotEmpty()

        /** Draw the given statuses into every placed widget. Safe to call from any thread. */
        fun render(context: Context, statuses: List<BbStatus>) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val views = baseViews(context)
            views.setViewVisibility(R.id.widget_message, if (statuses.isEmpty()) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_message, "No printers found on the Bambuddy server.")
            ROWS.forEachIndexed { i, row ->
                val s = statuses.getOrNull(i)
                if (s == null) {
                    views.setViewVisibility(row.root, View.GONE)
                    return@forEachIndexed
                }
                val active = s.connected && s.state in setOf("RUNNING", "PAUSE", "PREPARE", "SLICING")
                val pct = (s.progress ?: 0.0).roundToInt().coerceIn(0, 100)
                views.setViewVisibility(row.root, View.VISIBLE)
                views.setTextViewText(row.name, s.name)
                views.setTextViewText(row.status, statusLine(s, active))
                views.setTextColor(row.dot, colorFor(s))
                views.setTextViewText(row.pct, if (active) "$pct%" else "")
                views.setViewVisibility(row.progress, if (active) View.VISIBLE else View.GONE)
                views.setProgressBar(row.progress, 100, pct, false)
            }
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        private fun renderMessage(context: Context, message: String) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val views = baseViews(context)
            views.setViewVisibility(R.id.widget_message, View.VISIBLE)
            views.setTextViewText(R.id.widget_message, message)
            ROWS.forEach { views.setViewVisibility(it.root, View.GONE) }
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        private fun baseViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_printers)
            // Tap anywhere: open the app. Refresh icon: fetch now.
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context, 0, launch,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            val refresh = Intent(context, PrinterWidget::class.java).setAction(ACTION_REFRESH)
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                PendingIntent.getBroadcast(
                    context, 1, refresh,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            views.setTextViewText(
                R.id.widget_updated,
                "Updated " + LocalDateTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
            )
            return views
        }

        private fun statusLine(s: BbStatus, active: Boolean): String {
            if (!s.connected) return "Offline"
            val state = when {
                active && s.state != "PAUSE" && !s.stage.isNullOrBlank() -> s.stage
                else -> when (s.state) {
                    "IDLE" -> "Idle"
                    "RUNNING" -> "Printing"
                    "PAUSE" -> "Paused"
                    "FINISH" -> if (s.awaitingPlateClear) "Finished" else "Ready (last print finished)"
                    "FAILED" -> if (s.awaitingPlateClear) "Failed / stopped" else "Ready (last print stopped)"
                    "PREPARE" -> "Preparing"
                    "SLICING" -> "Slicing"
                    else -> s.state.lowercase().replaceFirstChar { it.uppercase() }
                }
            }
            val parts = mutableListOf(state)
            val left = s.remainingMin
            if (active && left != null && left > 0) {
                parts += if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left"
                val end = LocalDateTime.now().plusMinutes(left.toLong())
                parts += "done " + end.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            } else if (s.awaitingPlateClear && (s.state == "FINISH" || s.state == "FAILED")) {
                parts += "clear the plate"
            }
            return parts.joinToString("  ·  ")
        }

        private fun colorFor(s: BbStatus): Int = when {
            !s.connected -> 0xFFEF5350.toInt()
            s.state == "PAUSE" || s.hms.isNotEmpty() -> 0xFFF59E0B.toInt()
            s.state == "FAILED" && s.awaitingPlateClear -> 0xFFEF5350.toInt()
            s.state == "FINISH" && s.awaitingPlateClear -> 0xFF22C55E.toInt()
            s.state in setOf("RUNNING", "PREPARE", "SLICING") -> 0xFF3B82F6.toInt()
            else -> 0xFF6B7280.toInt()
        }
    }
}
