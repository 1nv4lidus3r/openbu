package org.cygnusx1.openbu.direct

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/** A printer that announced itself on the local network. */
data class FoundPrinter(val ip: String, val serial: String, val name: String, val model: String)

/**
 * Finds Bambu printers on the local network.
 *
 * Printers announce themselves every few seconds with a small SSDP "NOTIFY" message sent to UDP
 * ports 2021 and 1990 (multicast group 239.255.255.250 and broadcast). It carries the IP address
 * (Location), the serial number (USN), the printer's name and its model. We just listen for a
 * while; Android drops multicast packets unless a multicast lock is held, so take one meanwhile.
 */
object PrinterDiscovery {
    private const val TAG = "PrinterDiscovery"
    private val PORTS = listOf(2021, 1990)
    private const val GROUP = "239.255.255.250"

    /** Blocks for [durationMs], calling [onFound] for each new printer heard. Returns them all. */
    fun listen(context: Context, durationMs: Long = 8_000, onFound: (FoundPrinter) -> Unit = {}): List<FoundPrinter> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("bammate_discovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val found = LinkedHashMap<String, FoundPrinter>()
        val sockets = PORTS.mapNotNull { port -> openSocket(port) }
        try {
            if (sockets.isEmpty()) throw IllegalStateException("Couldn't listen on the network")
            val threads = sockets.map { socket ->
                Thread {
                    val buf = ByteArray(2048)
                    val end = System.currentTimeMillis() + durationMs
                    while (System.currentTimeMillis() < end && !socket.isClosed) {
                        try {
                            socket.soTimeout = (end - System.currentTimeMillis()).coerceAtLeast(1).toInt()
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val printer = parse(text, packet.address?.hostAddress) ?: continue
                            val isNew = synchronized(found) { found.put(printer.serial, printer) == null }
                            if (isNew) onFound(printer)
                        } catch (_: SocketTimeoutException) {
                            break
                        } catch (e: Exception) {
                            if (!socket.isClosed) Log.d(TAG, "receive failed: ${e.message}")
                            break
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
            threads.forEach { it.join(durationMs + 1_000) }
        } finally {
            sockets.forEach { runCatching { it.close() } }
            runCatching { lock?.release() }
        }
        return synchronized(found) { found.values.toList() }
    }

    private fun openSocket(port: Int): MulticastSocket? = try {
        MulticastSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(port))
            @Suppress("DEPRECATION")
            runCatching { joinGroup(InetAddress.getByName(GROUP)) }
        }
    } catch (e: Exception) {
        Log.d(TAG, "Can't listen on port $port: ${e.message}")
        null
    }

    /** Parses one announcement; returns null when it isn't from a Bambu printer. */
    fun parse(message: String, senderIp: String?): FoundPrinter? {
        val headers = message.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }
            .toMap()
        val nt = headers["nt"] ?: headers["st"] ?: ""
        val isBambu = nt.contains("bambulab", ignoreCase = true) || headers.keys.any { it.endsWith(".bambu.com") }
        if (!isBambu) return null
        val serial = headers["usn"]?.substringAfter("uuid:")?.substringBefore("::")?.trim().orEmpty()
        val ip = headers["location"]?.removePrefix("http://")?.substringBefore('/')?.substringBefore(':')?.trim()
            ?.ifBlank { null } ?: senderIp ?: return null
        if (serial.isBlank()) return null
        val model = modelFromSerial(serial).ifBlank { headers["devmodel.bambu.com"].orEmpty() }
        return FoundPrinter(ip = ip, serial = serial, name = headers["devname.bambu.com"].orEmpty(), model = model)
    }
}
