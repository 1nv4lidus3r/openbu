package org.cygnusx1.openbu.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.bambuddy.BambuddyClient
import org.cygnusx1.openbu.bambuddy.BbPlug
import org.cygnusx1.openbu.bambuddy.BbStatus
import org.cygnusx1.openbu.widget.PrinterWidget
import org.cygnusx1.openbu.network.BambuMqttClient
import org.cygnusx1.openbu.network.SavedPrinter

/**
 * Foreground service that keeps its own MQTT connection to each saved printer so print
 * alerts still arrive when the app screen is closed.
 *
 * It uses a separate MQTT client id from the UI (see [NotifyPrefs.backgroundClientId]) and
 * does not touch the ViewModel, so the existing UI connection logic is unchanged.
 */
class PrinterMonitorService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val monitors = mutableMapOf<String, Pair<SavedPrinter, Job>>()
    private var bambuddyWatch: Pair<NotifyPrefs.BambuddyConfig, Job>? = null
    private lateinit var notifier: PrinterNotifier

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotifyChannels.ensure(this)
        notifier = PrinterNotifier(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must enter the foreground within a few seconds of being started.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        reconcile()
        return START_STICKY
    }

    override fun onDestroy() {
        job.cancel() // each monitor closes its socket in a finally block
        monitors.clear()
        bambuddyWatch = null
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        return try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            val pending = launch?.let {
                android.app.PendingIntent.getActivity(
                    this, 0, it,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            val notification = NotificationCompat.Builder(this, NotifyChannels.SERVICE)
                .setSmallIcon(R.drawable.ic_stat_printer)
                .setContentTitle(getString(R.string.notify_service_title))
                .setContentText(getString(R.string.notify_service_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pending)
                .build()
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, FOREGROUND_ID, notification, type)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start in foreground", e)
            false
        }
    }

    /**
     * Match the running watchers to the settings: poll the Bambuddy server normally, or keep
     * direct MQTT monitors per saved printer when the app is in fallback mode.
     */
    private fun reconcile() {
        val prefs = NotifyPrefs.open(this)
        if (prefs == null || !prefs.getBoolean(NotifyPrefs.KEY_ENABLED, false)) {
            stopSelf()
            return
        }

        val bb = NotifyPrefs.bambuddyConfig(prefs)
        if (bb != null) {
            // Bambuddy mode: no direct printer connections at all.
            monitors.values.forEach { it.second.cancel() }
            monitors.clear()
            if (bambuddyWatch?.first != bb) {
                bambuddyWatch?.second?.cancel()
                bambuddyWatch = bb to scope.launch { watchBambuddy(bb, prefs) }
            }
            return
        }
        bambuddyWatch?.second?.cancel()
        bambuddyWatch = null

        val wanted = NotifyPrefs.savedPrinters(prefs)
            .filter { it.accessCode.isNotBlank() }
            .associateBy { it.serialNumber }

        monitors.keys.filter { it !in wanted }.forEach { monitors.remove(it)?.second?.cancel() }
        for ((serial, printer) in wanted) {
            val existing = monitors[serial]
            if (existing == null || existing.first != printer) {
                existing?.second?.cancel()
                monitors[serial] = printer to scope.launch { monitor(printer, prefs) }
            }
        }
        if (wanted.isEmpty()) stopSelf()
    }

    /** Poll the Bambuddy server for every printer's status and feed the notifier. */
    private suspend fun watchBambuddy(config: NotifyPrefs.BambuddyConfig, prefs: SharedPreferences) {
        val client = BambuddyClient(config.url, config.apiKey)
        // Smart plugs: which plug belongs to which printer (looked up every few minutes) and
        // whether it is on (read about once a minute). Used for "Power off" buttons and the
        // idle-but-powered reminder.
        val plugs = mutableMapOf<Int, BbPlug?>()
        val plugOn = mutableMapOf<Int, Boolean?>()
        var cycle = 0
        var waitMs = POLL_MS
        while (currentCoroutineContext().isActive) {
            try {
                val lookUpPlugs = cycle % PLUG_LOOKUP_EVERY == 0
                val readPlugs = cycle % PLUG_STATUS_EVERY == 0
                cycle++
                val seen = mutableListOf<BbStatus>()
                for (p in client.listPrinters()) {
                    try {
                        if (lookUpPlugs || !plugs.containsKey(p.id)) {
                            plugs[p.id] = try {
                                client.plugForPrinter(p.id)
                            } catch (e: Exception) {
                                plugs[p.id]
                            }
                        }
                        val plug = plugs[p.id]
                        if (readPlugs) {
                            plugOn[p.id] = plug?.let {
                                try {
                                    client.plugStatus(it.id).state?.uppercase()?.let { st -> st == "ON" }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                        val st = client.status(p.id)
                        seen += st
                        notifier.onBambuddy(st, prefs, plug, if (readPlugs) plugOn[p.id] else null)
                    } catch (e: Exception) {
                        Log.d(TAG, "Status for printer ${p.id} failed: ${e.message}")
                    }
                }
                if (cycle % MAINT_EVERY == 1) {
                    try {
                        notifier.onMaintenance(client.maintenanceOverview(), prefs)
                    } catch (e: Exception) {
                        Log.d(TAG, "Maintenance check failed: ${e.message}")
                    }
                }
                // Keep the home-screen widget current while alerts are running.
                if (PrinterWidget.hasWidgets(this)) PrinterWidget.render(this, seen)
                waitMs = POLL_MS
            } catch (e: Exception) {
                // Server down or phone off the network (e.g. away from home without VPN).
                Log.d(TAG, "Bambuddy poll failed: ${e.message}")
                waitMs = (waitMs * 2).coerceAtMost(MAX_POLL_BACKOFF_MS)
            }
            delay(waitMs)
        }
    }

    private suspend fun monitor(printer: SavedPrinter, prefs: SharedPreferences) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val client = BambuMqttClient(
                printer.ip,
                printer.accessCode,
                printer.serialNumber,
                clientId = NotifyPrefs.backgroundClientId(prefs),
                rawSocketFactory = NotifyPrefs.proxySocketFactory(prefs, printer.serialNumber),
            )
            val attempt = Job()
            try {
                client.connect(CoroutineScope(attempt + Dispatchers.IO))

                val gotReport = withTimeoutOrNull(REPORT_TIMEOUT_MS) {
                    client.reportReceived.first { it }
                } == true

                if (gotReport) {
                    backoffMs = INITIAL_BACKOFF_MS
                    coroutineScope {
                        val collector = launch {
                            client.printerStatus.collect { notifier.onReport(printer, it, prefs) }
                        }
                        // Suspends until the connection drops (socket error or read timeout).
                        client.connected.first { !it }
                        collector.cancel()
                    }
                } else if (client.connectionError.value?.contains("rejected", ignoreCase = true) == true) {
                    // Wrong access code: retrying forever would just hammer the printer.
                    Log.w(TAG, "Access code rejected for ${printer.serialNumber}; not retrying")
                    return
                }
            } finally {
                attempt.cancel()
                client.close()
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    companion object {
        private const val TAG = "PrinterMonitor"
        private const val FOREGROUND_ID = 1
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val REPORT_TIMEOUT_MS = 30_000L
        private const val POLL_MS = 15_000L
        private const val MAX_POLL_BACKOFF_MS = 120_000L
        private const val PLUG_LOOKUP_EVERY = 20 // ~5 min
        private const val PLUG_STATUS_EVERY = 4 // ~1 min
        private const val MAINT_EVERY = 120 // ~30 min

        /**
         * Make the service match the current settings: running when notifications are enabled
         * and at least one printer is saved, stopped otherwise. Call from a foreground context
         * (Android restricts starting foreground services from the background).
         */
        fun sync(context: Context) {
            try {
                val app = context.applicationContext
                val prefs = NotifyPrefs.open(app)
                val intent = Intent(app, PrinterMonitorService::class.java)
                val shouldRun = prefs != null &&
                    prefs.getBoolean(NotifyPrefs.KEY_ENABLED, false) &&
                    NotificationManagerCompat.from(app).areNotificationsEnabled() &&
                    (NotifyPrefs.bambuddyConfig(prefs) != null || NotifyPrefs.savedPrinters(prefs).isNotEmpty())
                if (shouldRun) {
                    ContextCompat.startForegroundService(app, intent)
                } else {
                    app.stopService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "sync failed", e)
            }
        }
    }
}
