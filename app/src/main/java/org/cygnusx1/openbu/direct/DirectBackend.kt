package org.cygnusx1.openbu.direct

import android.content.Context
import android.util.Log
import org.cygnusx1.openbu.bambuddy.BbObjects
import org.cygnusx1.openbu.bambuddy.BbPrinter
import org.cygnusx1.openbu.bambuddy.BbStatus
import org.cygnusx1.openbu.bambuddy.PrinterBackend
import org.cygnusx1.openbu.notify.NotifyPrefs
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Talks to the saved printers directly over the local network, without a Bambuddy server.
 *
 * One connection per printer is shared by everything in the app (the home screen and the
 * background alerts), so the printer only ever sees one BamMate client. Whoever needs the
 * connections calls [acquire]; when the last user calls [release] they are closed.
 */
object DirectBackend : PrinterBackend {
    private const val TAG = "DirectBackend"
    private const val PUSHALL_EVERY_MS = 5 * 60_000L
    private const val FIRST_BACKOFF_MS = 3_000L
    private const val MAX_BACKOFF_MS = 60_000L

    override val isDirect: Boolean get() = true

    private class Link(val printer: DirectPrinter, val clientId: String) {
        val state = DirectState()
        @Volatile var client: LanMqttClient? = null
        @Volatile var connected = false
        @Volatile var error: String? = null
        @Volatile var running = true
        var thread: Thread? = null
    }

    private val lock = Any()
    private val links = LinkedHashMap<Int, Link>()
    private val users = HashSet<String>()
    @Volatile private var printers: List<DirectPrinter> = emptyList()

    /** Start (or keep) the connections for [user], e.g. "screen" or "alerts". */
    fun acquire(context: Context, user: String) {
        synchronized(lock) {
            users += user
            reloadLocked(context)
        }
    }

    /** [user] no longer needs the connections; the last one out closes them. */
    fun release(user: String) {
        synchronized(lock) {
            users -= user
            if (users.isEmpty()) {
                links.values.forEach { stop(it) }
                links.clear()
            }
        }
    }

    /** Re-read the saved printers (after adding, editing or removing one). */
    fun reload(context: Context) {
        synchronized(lock) {
            if (users.isNotEmpty()) reloadLocked(context) else printers = loadPrinters(context)
        }
    }

    /** Waits until [printerId] is connected and has reported its state. False on timeout. */
    fun awaitConnected(printerId: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val l = synchronized(lock) { links[printerId] } ?: return false
            if (l.connected && !l.state.isEmpty()) return true
            if (!l.running) return false
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    /** The saved printers, as last loaded. */
    fun savedPrinters(context: Context): List<DirectPrinter> = loadPrinters(context)

    private fun loadPrinters(context: Context): List<DirectPrinter> {
        val prefs = NotifyPrefs.open(context) ?: return emptyList()
        return DirectStore.load(prefs)
    }

    private fun reloadLocked(context: Context) {
        val prefs = NotifyPrefs.open(context)
        val list = if (prefs == null) emptyList() else DirectStore.load(prefs)
        printers = list
        val clientId = prefs?.let { clientId(it) } ?: "bammate_${UUID.randomUUID().toString().take(8)}"
        val wanted = list.associateBy { it.id }
        // Close links for removed or changed printers.
        links.entries.removeAll { (id, link) ->
            val keep = wanted[id] == link.printer
            if (!keep) stop(link)
            !keep
        }
        for (p in list) {
            if (p.id !in links) {
                val link = Link(p, clientId)
                links[p.id] = link
                start(link)
            }
        }
    }

    private fun clientId(prefs: android.content.SharedPreferences): String =
        prefs.getString("direct_client_id", null) ?: run {
            // MQTT 3.1.1 brokers must accept ids up to 23 characters.
            val id = "bammate_" + UUID.randomUUID().toString().replace("-", "").take(12)
            prefs.edit().putString("direct_client_id", id).apply()
            id
        }

    // ------------------------------------------------------------------ connection loop

    private fun start(link: Link) {
        link.thread = Thread({ run(link) }, "direct-${link.printer.serial}").apply {
            isDaemon = true
            start()
        }
    }

    private fun stop(link: Link) {
        link.running = false
        link.thread?.interrupt()
        runCatching { link.client?.close() }
        link.connected = false
    }

    private fun run(link: Link) {
        val p = link.printer
        if (p.accessCode.isBlank()) {
            link.error = "Add the printer's access code to connect"
            return
        }
        var backoff = FIRST_BACKOFF_MS
        while (link.running) {
            val closed = CountDownLatch(1)
            val client = LanMqttClient(
                host = p.ip,
                serial = p.serial,
                accessCode = p.accessCode,
                clientId = link.clientId,
                onMessage = { link.state.merge(it) },
                onClosed = { closed.countDown() },
            )
            link.client = client
            try {
                client.connect()
                link.connected = true
                link.error = null
                backoff = FIRST_BACKOFF_MS
                client.publish(DirectCommands.pushAll())
                // Ask for the full state now and then, in case a partial update was missed.
                while (link.running && !closed.await(PUSHALL_EVERY_MS, TimeUnit.MILLISECONDS)) {
                    runCatching { client.publish(DirectCommands.pushAll()) }
                }
            } catch (e: LanMqttClient.AuthException) {
                link.error = "The printer rejected the access code"
                Log.w(TAG, "Access code rejected by ${p.serial}; not retrying")
                link.running = false
            } catch (e: InterruptedException) {
                link.running = false
            } catch (e: Exception) {
                link.error = describe(e)
                Log.d(TAG, "Connection to ${p.serial} failed: ${e.message}")
            } finally {
                link.connected = false
                runCatching { client.close() }
            }
            if (!link.running) break
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException, is ConnectException, is NoRouteToHostException ->
            "Can't reach the printer. Is it on, and is the phone on the same network?"
        is java.io.EOFException -> "The printer closed the connection"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun link(id: Int): Link =
        synchronized(lock) { links[id] } ?: throw IOException("This printer isn't connected")

    private fun send(id: Int, json: String) {
        val l = link(id)
        val c = l.client
        if (!l.connected || c == null) throw IOException(l.error ?: "Not connected to ${l.printer.displayName} yet")
        c.publish(json)
    }

    // ------------------------------------------------------------------ PrinterBackend

    override fun listPrinters(): List<BbPrinter> = printers.map { BbPrinter(it.id, it.displayName, it.model, it.serial) }

    override fun status(printerId: Int): BbStatus {
        val l = synchronized(lock) { links[printerId] }
        val p = l?.printer ?: printers.firstOrNull { it.id == printerId }
            ?: throw IOException("Unknown printer")
        val state = l?.state?.snapshot() ?: JSONObject()
        val status = toStatus(printerId, p.displayName, p.serial, state, l?.connected == true)
        return if (status.connected) status else status.copy(note = l?.error ?: "Connecting...")
    }

    override fun setBedTemp(id: Int, target: Int) =
        send(id, DirectCommands.gcode("M140 S${target.coerceIn(0, 120)}"))

    override fun setNozzleTemp(id: Int, target: Int, nozzle: Int) {
        val t = target.coerceIn(0, 320)
        val dual = runCatching { status(id).dualNozzle }.getOrDefault(false)
        send(id, DirectCommands.gcode(if (dual) "M104 T$nozzle S$t" else "M104 S$t"))
    }

    override fun setChamberTemp(id: Int, target: Int) =
        send(id, DirectCommands.gcode("M141 S${target.coerceIn(0, 65)}"))

    override fun setFanSpeed(id: Int, fan: String, percent: Int) {
        val index = when (fan) {
            "part" -> 1
            "aux" -> 2
            "chamber" -> 3
            else -> throw IllegalArgumentException("Unknown fan $fan")
        }
        val pwm = (percent.coerceIn(0, 100) * 255 / 100.0).roundToInt()
        send(id, DirectCommands.gcode("M106 P$index S$pwm"))
    }

    override fun setPrintSpeed(id: Int, mode: Int) = send(id, DirectCommands.speed(mode))

    override fun setChamberLight(id: Int, on: Boolean) {
        send(id, DirectCommands.light("chamber_light", on))
        // The H2 series has a second chamber light.
        val model = link(id).printer.model
        if (model == "H2D" || model == "H2S") send(id, DirectCommands.light("chamber_light2", on))
    }

    override fun pause(id: Int) = send(id, DirectCommands.pause())
    override fun resume(id: Int) = send(id, DirectCommands.resume())
    override fun stop(id: Int) = send(id, DirectCommands.stop())

    /** Only Bambuddy keeps a "plate needs clearing" flag; nothing to do on a direct connection. */
    override fun clearPlate(id: Int) = Unit

    override fun printObjects(id: Int, reload: Boolean): BbObjects =
        throw UnsupportedOperationException("Skipping objects needs a Bambuddy server for now")

    override fun skipObjects(id: Int, objectIds: List<Int>) = send(id, DirectCommands.skipObjects(objectIds))
}
