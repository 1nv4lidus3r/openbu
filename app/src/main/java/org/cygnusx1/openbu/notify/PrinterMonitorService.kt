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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.bambuddy.BambuddyClient
import org.cygnusx1.openbu.bambuddy.BbPlug
import org.cygnusx1.openbu.bambuddy.BbStatus
import org.cygnusx1.openbu.direct.DirectBackend
import org.cygnusx1.openbu.widget.PrinterWidget

/**
 * Foreground service that keeps watching the printers so print alerts still arrive when the
 * app screen is closed: it polls the Bambuddy server, or in direct mode reads the printers'
 * own connections (shared with the home screen through [DirectBackend]).
 */
class PrinterMonitorService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private var directWatch: Job? = null
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
        job.cancel()
        directWatch = null
        bambuddyWatch = null
        DirectBackend.release(ALERTS_USER)
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
     * Match the running watcher to the settings: poll the Bambuddy server normally, or read the
     * direct printer connections when the app is in direct mode.
     */
    private fun reconcile() {
        val prefs = NotifyPrefs.open(this)
        if (prefs == null || !prefs.getBoolean(NotifyPrefs.KEY_ENABLED, false)) {
            stopSelf()
            return
        }

        val bb = NotifyPrefs.bambuddyConfig(prefs)
        if (bb != null) {
            stopDirect()
            if (bambuddyWatch?.first != bb) {
                bambuddyWatch?.second?.cancel()
                bambuddyWatch = bb to scope.launch { watchBambuddy(bb, prefs) }
            }
            return
        }
        bambuddyWatch?.second?.cancel()
        bambuddyWatch = null

        if (!NotifyPrefs.directMode(prefs) || NotifyPrefs.directPrinters(prefs).isEmpty()) {
            stopDirect()
            stopSelf()
            return
        }
        // Picks up added or removed printers; the watcher reads the current list every round.
        DirectBackend.acquire(this, ALERTS_USER)
        if (directWatch?.isActive != true) directWatch = scope.launch { watchDirect(prefs) }
    }

    private fun stopDirect() {
        directWatch?.cancel()
        directWatch = null
        DirectBackend.release(ALERTS_USER)
    }

    /** Read every direct printer's status every few seconds and feed the notifier. */
    private suspend fun watchDirect(prefs: SharedPreferences) {
        var cycle = 0
        while (currentCoroutineContext().isActive) {
            val seen = mutableListOf<BbStatus>()
            for (p in DirectBackend.listPrinters()) {
                try {
                    val st = DirectBackend.status(p.id)
                    seen += st
                    notifier.onBambuddy(st, prefs, direct = true)
                } catch (e: Exception) {
                    Log.d(TAG, "Direct status for ${p.id} failed: ${e.message}")
                }
            }
            // The widget doesn't need every reading.
            if (cycle++ % 3 == 0 && PrinterWidget.hasWidgets(this)) PrinterWidget.render(this, seen)
            delay(DIRECT_POLL_MS)
        }
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

    companion object {
        private const val TAG = "PrinterMonitor"
        private const val FOREGROUND_ID = 1
        private const val ALERTS_USER = "alerts"
        private const val DIRECT_POLL_MS = 5_000L
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
                    (
                        NotifyPrefs.bambuddyConfig(prefs) != null ||
                            (NotifyPrefs.directMode(prefs) && NotifyPrefs.directPrinters(prefs).isNotEmpty())
                        )
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
