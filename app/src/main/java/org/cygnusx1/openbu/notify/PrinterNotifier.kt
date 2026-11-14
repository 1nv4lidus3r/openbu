package org.cygnusx1.openbu.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.bambuddy.BbMaintPrinter
import org.cygnusx1.openbu.bambuddy.BbPlug
import org.cygnusx1.openbu.bambuddy.BbStatus
import org.cygnusx1.openbu.bambuddy.displayName
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object NotifyChannels {
    const val SERVICE = "bammate_service"
    const val FINISHED = "bammate_print_finished"
    const val FAILED = "bammate_print_failed"
    const val PAUSED = "bammate_print_paused"
    const val HMS = "bammate_hms"
    const val REMINDERS = "bammate_reminders"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Remove the channels from before the rename so they don't linger in system settings.
        listOf("openbu_service", "openbu_print_finished", "openbu_print_failed", "openbu_print_paused", "openbu_hms")
            .forEach { if (nm.getNotificationChannel(it) != null) nm.deleteNotificationChannel(it) }
        fun make(id: String, nameRes: Int, importance: Int) {
            if (nm.getNotificationChannel(id) == null) {
                nm.createNotificationChannel(NotificationChannel(id, context.getString(nameRes), importance))
            }
        }
        make(SERVICE, R.string.notify_channel_service, NotificationManager.IMPORTANCE_LOW)
        make(FINISHED, R.string.notify_channel_finished, NotificationManager.IMPORTANCE_HIGH)
        make(FAILED, R.string.notify_channel_failed, NotificationManager.IMPORTANCE_HIGH)
        make(PAUSED, R.string.notify_channel_paused, NotificationManager.IMPORTANCE_HIGH)
        make(HMS, R.string.notify_channel_hms, NotificationManager.IMPORTANCE_HIGH)
        make(REMINDERS, R.string.notify_channel_reminders, NotificationManager.IMPORTANCE_HIGH)
    }
}

/** A button on a notification, handled by [NotificationActionReceiver]. */
data class NotifAction(val label: String, val action: String, val printerId: Int, val plugId: Int = -1)

/**
 * Watches printer status updates and posts a notification when something worth telling the
 * user about happens.
 *
 * Safe against false alarms on (re)connect: the first update seen for a printer is only
 * recorded as a baseline. After that, alerts fire only on real transitions. State is kept per
 * printer across reconnects, so a print that finishes while the connection is down is still
 * reported once the connection is back.
 */
class PrinterNotifier(private val context: Context) {

    private class Track(var state: String, var hmsCodes: Set<String>)

    /** Extra per-printer memory for the reminder alerts (Bambuddy mode only). */
    private class Reminders(
        var plateSince: Long?,
        var plateAlerted: Boolean,
        var almostJob: String?,
        var idleSince: Long?,
        var idleAlerted: Boolean,
    )

    private val tracks = ConcurrentHashMap<String, Track>()
    private val reminders = ConcurrentHashMap<String, Reminders>()
    /** Last known drying state per AMS ("bb:<printer>:<ams>"). */
    private val drying = ConcurrentHashMap<String, Boolean>()

    /**
     * A printer's latest status, from the Bambuddy server or a direct connection ([direct]).
     * plug / plugOn are the printer's smart plug and whether it is on, when known (Bambuddy only).
     */
    fun onBambuddy(
        s: BbStatus,
        prefs: SharedPreferences,
        plug: BbPlug? = null,
        plugOn: Boolean? = null,
        direct: Boolean = false,
    ) {
        // An offline printer has no meaningful state; keep the last known one until it is back.
        if (!s.connected || s.state.isBlank()) return
        val name = s.name.ifBlank { "Printer ${s.id}" }
        val job = s.job.substringAfterLast('/')
        val pct = s.progress?.roundToInt() ?: 0
        val key = "bb:" + s.id
        // Offer "Power off" only when Bambuddy isn't already going to do it automatically.
        val powerOff = if (plug != null && !plug.autoOff) {
            listOf(NotifAction("Power off", NotificationActionReceiver.ACTION_POWER_OFF, s.id, plug.id))
        } else {
            emptyList()
        }
        val active = s.state in ACTIVE_STATES
        // Only Bambuddy keeps a "plate needs clearing" flag.
        val clearPlate = if (direct) {
            emptyList()
        } else {
            listOf(NotifAction("Plate cleared", NotificationActionReceiver.ACTION_CLEAR_PLATE, s.id))
        }

        onUpdate(
            key = key,
            name = name,
            state = s.state,
            hms = s.hms.associate { it.code to it.description },
            jobText = if (job.isBlank()) name else "$job ($name)",
            progress = if (s.layer != null && s.totalLayers != null && s.totalLayers > 0) {
                " - $pct%, layer ${s.layer}/${s.totalLayers}"
            } else {
                " - $pct%"
            },
            prefs = prefs,
            actionsFor = { type ->
                when (type) {
                    TYPE_FINISHED, TYPE_FAILED -> clearPlate + powerOff
                    TYPE_PAUSED -> listOf(NotifAction("Resume", NotificationActionReceiver.ACTION_RESUME, s.id))
                    TYPE_HMS -> if (s.state == "RUNNING") {
                        listOf(NotifAction("Pause print", NotificationActionReceiver.ACTION_PAUSE, s.id))
                    } else {
                        emptyList()
                    }
                    else -> emptyList()
                }
            },
        )

        // Drying finished (or stopped) on an AMS unit.
        s.ams.forEach { u ->
            val dk = "$key:${u.id}"
            val was = drying.put(dk, u.drying)
            if (was == true && !u.drying && prefs.getBoolean(NotifyPrefs.KEY_FINISHED, true)) {
                val what = u.dryFilament?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                // Fires both when the time runs out and when someone stops it, so say "ended".
                post(dk, TYPE_DRY, NotifyChannels.REMINDERS, "Drying ended",
                    "${u.displayName()} on $name has stopped drying$what.")
            }
        }

        // --- reminders ---
        val now = System.currentTimeMillis()
        val idle = !active && plugOn == true && plug != null && !plug.autoOff
        val r = reminders[key]
        if (r == null) {
            // Baseline: don't fire anything for a situation that was already true at start-up.
            reminders[key] = Reminders(
                plateSince = if (s.awaitingPlateClear) now else null,
                plateAlerted = s.awaitingPlateClear,
                almostJob = if (active && (s.remainingMin ?: Int.MAX_VALUE) <= ALMOST_MIN) s.job else null,
                idleSince = if (idle) now else null,
                idleAlerted = false,
            )
            return
        }

        // Almost done: once per job, when ~10 minutes are left.
        val left = s.remainingMin
        if (s.state == "RUNNING" && left != null && left in 1..ALMOST_MIN && r.almostJob != s.job) {
            r.almostJob = s.job
            if (prefs.getBoolean(NotifyPrefs.KEY_ALMOST, true)) {
                post(key, TYPE_ALMOST, NotifyChannels.REMINDERS, "Almost done",
                    "${if (job.isBlank()) "The print" else job} on $name finishes in about $left min (${clockIn(left)}).")
            }
        }
        if (!active) cancel(key, TYPE_ALMOST)

        // Plate still needs clearing a while after the print ended.
        if (s.awaitingPlateClear) {
            val since = r.plateSince ?: now.also { r.plateSince = it }
            if (!r.plateAlerted && now - since >= PLATE_REMIND_MS) {
                r.plateAlerted = true
                if (prefs.getBoolean(NotifyPrefs.KEY_PLATE, true)) {
                    post(key, TYPE_PLATE, NotifyChannels.REMINDERS, "Plate needs clearing",
                        "$name finished a while ago and is waiting for the build plate to be cleared.",
                        clearPlate)
                }
            }
        } else {
            r.plateSince = null
            r.plateAlerted = false
            cancel(key, TYPE_PLATE)
        }

        // Idle but still powered for an hour (only when auto power-off isn't handling it).
        if (idle) {
            val since = r.idleSince ?: now.also { r.idleSince = it }
            if (!r.idleAlerted && now - since >= IDLE_REMIND_MS) {
                r.idleAlerted = true
                if (prefs.getBoolean(NotifyPrefs.KEY_IDLE_POWER, true)) {
                    post(key, TYPE_IDLE, NotifyChannels.REMINDERS, "Still powered on",
                        "$name has been idle for over an hour. Turn it off?", powerOff)
                }
            }
        } else if (plugOn != null || active) {
            // Only reset on real information (plug status is read less often than printer status).
            r.idleSince = null
            r.idleAlerted = false
            cancel(key, TYPE_IDLE)
        }
    }

    private var maintBaseline: Set<Int>? = null

    /** Alert once when a maintenance item becomes due (items already due at start-up are the baseline). */
    fun onMaintenance(list: List<BbMaintPrinter>, prefs: SharedPreferences) {
        val due = list.flatMap { p -> p.items.filter { it.enabled && it.isDue }.map { p to it } }
        val dueIds = due.map { it.second.id }.toSet()
        val before = maintBaseline
        maintBaseline = dueIds
        if (before == null || !prefs.getBoolean(NotifyPrefs.KEY_MAINT, true)) return
        due.filter { it.second.id !in before }.forEach { (p, item) ->
            post("maint:${item.id}", TYPE_MAINT, NotifyChannels.REMINDERS, "Maintenance due",
                "${p.printerName}: ${item.name} (${item.dueText.lowercase()})")
        }
    }

    /**
     * Shared transition logic for finished / failed / paused / HMS alerts.
     */
    private fun onUpdate(
        key: String,
        name: String,
        state: String,
        hms: Map<String, String?>,
        jobText: String,
        progress: String,
        prefs: SharedPreferences,
        actionsFor: (Int) -> List<NotifAction> = { emptyList() },
    ) {
        val hmsNow = hms.keys.filter { it.isNotBlank() }.toSet()
        val track = tracks[key]
        if (track == null) {
            tracks[key] = Track(state, hmsNow)
            return
        }

        val prev = track.state
        if (state != prev) {
            // A pause alert is stale once the job resumes or ends, so take it down.
            if (prev == "PAUSE") cancel(key, TYPE_PAUSED)
            when {
                state == "FINISH" && prev in ACTIVE_STATES &&
                    prefs.getBoolean(NotifyPrefs.KEY_FINISHED, true) ->
                    post(key, TYPE_FINISHED, NotifyChannels.FINISHED,
                        context.getString(R.string.notify_title_finished), jobText, actionsFor(TYPE_FINISHED))

                state == "FAILED" && prev in ACTIVE_STATES &&
                    prefs.getBoolean(NotifyPrefs.KEY_FAILED, true) ->
                    post(key, TYPE_FAILED, NotifyChannels.FAILED,
                        context.getString(R.string.notify_title_failed), jobText, actionsFor(TYPE_FAILED))

                state == "PAUSE" && prev == "RUNNING" &&
                    prefs.getBoolean(NotifyPrefs.KEY_PAUSED, true) ->
                    post(key, TYPE_PAUSED, NotifyChannels.PAUSED,
                        context.getString(R.string.notify_title_paused), jobText + progress, actionsFor(TYPE_PAUSED))
            }
        }

        val newHms = hmsNow - track.hmsCodes
        if (newHms.isNotEmpty() && prefs.getBoolean(NotifyPrefs.KEY_HMS, true)) {
            val lines = newHms.sorted().joinToString("\n") { code ->
                val d = hms[code]
                if (d.isNullOrBlank()) code else "$code: $d"
            }
            post(key, TYPE_HMS, NotifyChannels.HMS, context.getString(R.string.notify_title_hms),
                "$name: $lines", actionsFor(TYPE_HMS))
        }

        track.state = state
        track.hmsCodes = hmsNow
    }

    private fun clockIn(minutes: Int): String =
        LocalDateTime.now().plusMinutes(minutes.toLong())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    private fun cancel(key: String, type: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId(key, type))
    }

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled(), and the permission is requested in settings
    private fun post(
        key: String,
        type: Int,
        channel: String,
        title: String,
        text: String,
        actions: List<NotifAction> = emptyList(),
    ) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        NotifyChannels.ensure(context)

        val id = notificationId(key, type)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_printer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pending)

        actions.forEachIndexed { i, a ->
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(a.action)
                .putExtra(NotificationActionReceiver.EXTRA_PRINTER_ID, a.printerId)
                .putExtra(NotificationActionReceiver.EXTRA_PLUG_ID, a.plugId)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
                .putExtra(NotificationActionReceiver.EXTRA_TITLE, title)
            val pi = PendingIntent.getBroadcast(
                context, id * 4 + i, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, a.label, pi)
        }

        nm.notify(id, builder.build())
    }

    companion object {
        // gcode_state values that mean "a job is in progress". A transition from one of these
        // to FINISH or FAILED is a real end-of-job event; IDLE/FINISH/FAILED at connect are not.
        private val ACTIVE_STATES = setOf("PREPARE", "SLICING", "RUNNING", "PAUSE")

        private const val TYPE_FINISHED = 1
        private const val TYPE_FAILED = 2
        private const val TYPE_PAUSED = 3
        private const val TYPE_HMS = 4
        private const val TYPE_PLATE = 5
        private const val TYPE_ALMOST = 6
        private const val TYPE_IDLE = 7
        private const val TYPE_MAINT = 8
        private const val TYPE_DRY = 9

        private const val ALMOST_MIN = 10
        private const val PLATE_REMIND_MS = 15 * 60_000L
        private const val IDLE_REMIND_MS = 60 * 60_000L

        // One slot per printer and event type (types 1..9), so repeated events update rather than stack.
        fun notificationId(key: String, type: Int): Int = 100 + (key.hashCode() and 0xFFFF) * 10 + type
    }
}
