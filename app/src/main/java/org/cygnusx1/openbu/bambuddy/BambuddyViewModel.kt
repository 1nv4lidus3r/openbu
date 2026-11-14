package org.cygnusx1.openbu.bambuddy

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.openbu.direct.DirectBackend
import org.cygnusx1.openbu.notify.NotifyPrefs
import org.cygnusx1.openbu.notify.PrinterMonitorService

/**
 * State holder for the Bambuddy screen. Polls the server every few seconds while the screen
 * is visible, and runs control commands (then refreshes straight away).
 */
class BambuddyViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs by lazy { NotifyPrefs.open(application) }

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _statuses = MutableStateFlow<List<BbStatus>>(emptyList())
    val statuses: StateFlow<List<BbStatus>> = _statuses.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** True once at least one refresh has finished (success or failure). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _power = MutableStateFlow<Map<Int, BbPower>>(emptyMap())
    /** Smart plug info per printer id (only printers that have a plug assigned). */
    val power: StateFlow<Map<Int, BbPower>> = _power.asStateFlow()

    // Plug assignments change rarely, so they're looked up every PLUG_LOOKUP_EVERY polls;
    // plug status (watts/on/off) is read every POWER_EVERY polls.
    private val plugCache = mutableMapOf<Int, BbPlug?>()
    private var pollCount = 0

    private val _scheduledDryings = MutableStateFlow<List<BbScheduledDry>>(emptyList())
    /** Drying runs scheduled in Bambuddy that haven't started yet. */
    val scheduledDryings: StateFlow<List<BbScheduledDry>> = _scheduledDryings.asStateFlow()
    @Volatile private var dryingsDirty = true

    private val _maintenance = MutableStateFlow<List<BbMaintPrinter>>(emptyList())
    /** Maintenance overview, refreshed every few minutes (and whenever the screen loads it). */
    val maintenance: StateFlow<List<BbMaintPrinter>> = _maintenance.asStateFlow()

    fun setMaintenance(list: List<BbMaintPrinter>) {
        _maintenance.value = list
    }

    private val _fallbackMode = MutableStateFlow(false)
    /**
     * When true the home screen talks to the saved printers directly over the local network
     * ([DirectBackend]) instead of through the Bambuddy server. Off by default.
     */
    val fallbackMode: StateFlow<Boolean> = _fallbackMode.asStateFlow()

    private var client: BambuddyClient? = null
    private var pollJob: Job? = null

    init {
        _baseUrl.value = prefs?.getString(KEY_URL, "") ?: ""
        _apiKey.value = prefs?.getString(KEY_API, "") ?: ""
        _fallbackMode.value = prefs?.getBoolean(KEY_FALLBACK, false) ?: false
        rebuildClient()
    }

    fun setFallback(enabled: Boolean) {
        if (_fallbackMode.value == enabled) return
        val polling = pollJob?.isActive == true
        if (polling) stopPolling()
        _fallbackMode.value = enabled
        prefs?.edit()?.putBoolean(KEY_FALLBACK, enabled)?.commit()
        _statuses.value = emptyList()
        _power.value = emptyMap()
        _error.value = null
        _loaded.value = false
        pollCount = 0
        if (polling) startPolling()
        // Switch background alerts between Bambuddy polling and direct printer connections.
        PrinterMonitorService.sync(getApplication())
    }

    val configured: Boolean get() = _baseUrl.value.isNotBlank()

    private fun rebuildClient() {
        client = if (_baseUrl.value.isBlank()) null else BambuddyClient(_baseUrl.value, _apiKey.value)
    }

    fun saveConfig(url: String, key: String) {
        _baseUrl.value = url.trim()
        _apiKey.value = key.trim()
        prefs?.edit()?.putString(KEY_URL, _baseUrl.value)?.putString(KEY_API, _apiKey.value)?.commit()
        PrinterMonitorService.sync(getApplication())
        rebuildClient()
        _statuses.value = emptyList()
        _power.value = emptyMap()
        _maintenance.value = emptyList()
        _scheduledDryings.value = emptyList()
        dryingsDirty = true
        synchronized(plugCache) { plugCache.clear() }
        pollCount = 0
        _error.value = null
        _loaded.value = false
        viewModelScope.launch { refreshOnce() }
    }

    /** Where the printers are reached right now: directly, or through the Bambuddy server. */
    private fun backend(): PrinterBackend? = if (_fallbackMode.value) DirectBackend else client

    /** Call after adding, editing or removing a direct printer. */
    fun directPrintersChanged() {
        DirectBackend.reload(getApplication())
        PrinterMonitorService.sync(getApplication())
        _loaded.value = false
        refreshNow()
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        if (_fallbackMode.value) DirectBackend.acquire(getApplication(), SCREEN_USER)
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        DirectBackend.release(SCREEN_USER)
    }

    fun refreshNow() {
        viewModelScope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        val b = backend() ?: return
        try {
            val list = withContext(Dispatchers.IO) {
                val printers = b.listPrinters()
                coroutineScope {
                    printers.map { p ->
                        async(Dispatchers.IO) {
                            try {
                                b.status(p.id).let { st ->
                                    st.copy(
                                        model = st.model.ifBlank { p.model },
                                        serial = st.serial.ifBlank { p.serial },
                                    )
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "status failed for printer ${p.id}", e)
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }
            _statuses.value = list
            _error.value = null
            // Spool assignments from HaspelSync (works with either connection), every ~30 seconds.
            if (pollCount % SPOOLS_EVERY == 0) refreshSpools(list)
            // Smart plugs, drying schedules and maintenance only exist on the Bambuddy server.
            val c = b as? BambuddyClient
            if (c == null) {
                pollCount++
                return
            }
            if (pollCount % POWER_EVERY == 0) refreshPower(c, list.map { it.id }, lookupPlugs = pollCount % PLUG_LOOKUP_EVERY == 0)
            if (dryingsDirty || pollCount % DRYINGS_EVERY == 0) {
                dryingsDirty = false
                try {
                    _scheduledDryings.value = withContext(Dispatchers.IO) { c.scheduledDryings() }.filter { it.pending }
                } catch (e: Exception) {
                    Log.w(TAG, "scheduled dryings refresh failed", e)
                }
            }
            if (pollCount % MAINT_EVERY == 0) {
                try {
                    _maintenance.value = withContext(Dispatchers.IO) { c.maintenanceOverview() }
                } catch (e: Exception) {
                    Log.w(TAG, "maintenance refresh failed", e)
                }
            }
            pollCount++
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            _error.value = describe(e)
        } finally {
            _loaded.value = true
        }
    }

    private val _spools = MutableStateFlow<Map<Int, Map<String, HsSpool>>>(emptyMap())
    /** Per printer id: HaspelSync slot label ("A1", "HT-A", "External") to the assigned Spoolman spool. */
    val spools: StateFlow<Map<Int, Map<String, HsSpool>>> = _spools.asStateFlow()

    fun haspelSyncConfig(): Pair<String, String> =
        (prefs?.getString(KEY_HS_URL, "") ?: "") to (prefs?.getString(KEY_HS_KEY, "") ?: "")

    fun saveHaspelSync(url: String, key: String) {
        prefs?.edit()?.putString(KEY_HS_URL, url.trim())?.putString(KEY_HS_KEY, key.trim())?.apply()
        if (url.isBlank()) _spools.value = emptyMap()
        viewModelScope.launch { refreshSpools(_statuses.value) }
    }

    /** Asks HaspelSync for its printers, to check the address and key. */
    suspend fun testHaspelSync(url: String, key: String): List<HsPrinter> =
        withContext(Dispatchers.IO) { HaspelSyncClient(url, key).printers() }

    private suspend fun refreshSpools(printers: List<BbStatus>) {
        val (url, key) = haspelSyncConfig()
        if (url.isBlank() || key.isBlank() || printers.isEmpty()) return
        try {
            val hs = HaspelSyncClient(url, key)
            val result = withContext(Dispatchers.IO) {
                val known = hs.printers()
                val used = HashSet<String>()
                // Serial number first; the name only when the serial isn't known.
                val pairs = printers.mapNotNull { st ->
                    val bySerial = known.firstOrNull { st.serial.isNotBlank() && it.id.equals(st.serial, ignoreCase = true) }
                    val match = bySerial
                        ?: known.firstOrNull { it.id !in used && it.name.equals(st.name, ignoreCase = true) }
                        ?: known.filter { it.id !in used && HaspelSyncClient.namesMatch(it.name, st.name) }.singleOrNull()
                    match?.let { used += it.id; st.id to it.id }
                }
                coroutineScope {
                    pairs.map { (printerId, hsId) ->
                        async(Dispatchers.IO) {
                            try {
                                printerId to hs.spools(hsId)
                            } catch (e: Exception) {
                                Log.w(TAG, "HaspelSync spools failed for $hsId", e)
                                null
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
            }
            _spools.value = result
        } catch (e: Exception) {
            Log.w(TAG, "HaspelSync refresh failed", e)
        }
    }

    private suspend fun refreshPower(c: BambuddyClient, printerIds: List<Int>, lookupPlugs: Boolean) {
        val result = withContext(Dispatchers.IO) {
            coroutineScope {
                printerIds.map { id ->
                    async(Dispatchers.IO) {
                        try {
                            val plug = if (lookupPlugs || !plugCache.containsKey(id)) {
                                c.plugForPrinter(id).also { synchronized(plugCache) { plugCache[id] = it } }
                            } else {
                                synchronized(plugCache) { plugCache[id] }
                            }
                            if (plug == null) {
                                null
                            } else {
                                val st = try {
                                    c.plugStatus(plug.id)
                                } catch (e: Exception) {
                                    Log.w(TAG, "plug status failed for ${plug.id}", e)
                                    BbPlugStatus(null, false, null, null)
                                }
                                id to BbPower(plug, st)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "plug lookup failed for printer $id", e)
                            _power.value[id]?.let { id to it } // keep the last known value
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
        }
        _power.value = result
    }

    /** Switch a printer's smart plug, or its auto power-off setting, then read it back. */
    fun powerCommand(printerId: Int, block: BambuddyClient.(BbPlug) -> Unit) {
        val c = client ?: return
        val plug = _power.value[printerId]?.plug ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                c.block(plug)
                delay(1_000)
                refreshPower(c, _statuses.value.map { it.id }, lookupPlugs = true)
            } catch (e: Exception) {
                Log.w(TAG, "power command failed", e)
                _error.value = "Power command failed: " + describe(e)
            }
        }
    }

    fun describe(e: Exception): String = when (e) {
        is BambuddyException -> e.message ?: "Server error"
        is java.net.UnknownHostException -> "Can't find the server. Check the address."
        is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "Can't reach the server. Is it running, and is the phone on the same network?"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Run a read-only call off the main thread and return its result (errors are thrown to the caller). */
    suspend fun <T> query(block: BambuddyClient.() -> T): T {
        val c = client ?: throw IllegalStateException("No server configured")
        return withContext(Dispatchers.IO) { c.block() }
    }

    /** The current client, for long-running streams such as the camera. Null until a server is saved. */
    fun currentClient(): BambuddyClient? = client

    /**
     * Run a printer control (temperatures, fans, pause, ...) through whichever connection is in
     * use, then refresh. Errors show in the banner.
     */
    fun control(block: PrinterBackend.() -> Unit) {
        val b = backend() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                b.block()
                delay(700) // let the printer apply the change before reading it back
                refreshOnce()
            } catch (e: Exception) {
                Log.w(TAG, "control failed", e)
                _error.value = "Command failed: " + describe(e)
            }
        }
    }

    /** Run a Bambuddy-only command off the main thread, then refresh. Errors show in the banner. */
    fun command(block: BambuddyClient.() -> Unit) {
        val c = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                c.block()
                dryingsDirty = true // cheap; picks up a just-scheduled or cancelled drying
                delay(700) // let the printer apply the change before reading it back
                refreshOnce()
            } catch (e: Exception) {
                Log.w(TAG, "command failed", e)
                _error.value = "Command failed: " + describe(e)
            }
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    companion object {
        private const val TAG = "BambuddyVM"
        private const val SCREEN_USER = "screen"
        private const val KEY_URL = "bb_base_url"
        private const val KEY_API = "bb_api_key"
        private const val KEY_FALLBACK = "bb_fallback_mode"
        private const val KEY_HS_URL = "hs_base_url"
        private const val KEY_HS_KEY = "hs_api_key"
        private const val SPOOLS_EVERY = 10 // polls (about 30 seconds)
        private const val POLL_MS = 3_000L
        private const val POWER_EVERY = 3 // ~9 s
        private const val PLUG_LOOKUP_EVERY = 30 // ~90 s
        private const val MAINT_EVERY = 100 // ~5 min
        private const val DRYINGS_EVERY = 10 // ~30 s
    }
}
