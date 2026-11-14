package org.cygnusx1.openbu.direct

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Minimal MQTT 3.1.1 client for a Bambu Lab printer in LAN mode.
 *
 * The printer runs an MQTT broker on port 8883 (TLS with a self-signed certificate). Log in as
 * "bblp" with the printer's access code, subscribe to device/<serial>/report for status and
 * publish JSON commands to device/<serial>/request. QoS 0 only, which is all the printer needs.
 *
 * Blocking API on plain threads: [connect] performs the handshake, then a reader thread
 * delivers messages to [onMessage] until the connection drops, when [onClosed] is called once.
 */
class LanMqttClient(
    private val host: String,
    private val serial: String,
    private val accessCode: String,
    private val clientId: String,
    private val port: Int = 8883,
    private val onMessage: (String) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
) {
    class AuthException(message: String) : IOException(message)

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closed = false
    private val writeLock = Any()

    val reportTopic get() = "device/$serial/report"
    val requestTopic get() = "device/$serial/request"

    /** Connects, logs in and subscribes. Throws [AuthException] for a wrong access code. */
    fun connect(timeoutMs: Int = 8_000) {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        val ssl = trustAllContext().socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        socket = ssl
        val o = ssl.getOutputStream()
        out = o
        val input = BufferedInputStream(ssl.getInputStream())

        sendPacket(0x10, connectBody())
        val (type, body) = readPacket(input)
        if (type != 2 || body.size < 2) throw IOException("Unexpected reply from printer (packet $type)")
        when (val rc = body[1].toInt() and 0xFF) {
            0 -> Unit
            4, 5 -> throw AuthException("The printer rejected the access code")
            else -> throw IOException("The printer refused the connection (code $rc)")
        }

        // SUBSCRIBE (packet id 1, QoS 0)
        val sub = ByteArrayOutputStream()
        sub.write(0); sub.write(1)
        writeString(sub, reportTopic)
        sub.write(0)
        sendPacket(0x82, sub.toByteArray())

        ssl.soTimeout = 90_000 // the printer reports at least every few seconds; pings keep it alive too
        Thread({ readLoop(input) }, "lan-mqtt-$serial").apply { isDaemon = true }.start()
        Thread({ pingLoop() }, "lan-mqtt-ping-$serial").apply { isDaemon = true }.start()
    }

    /** Publishes a JSON command to the printer. */
    fun publish(json: String) {
        val body = ByteArrayOutputStream()
        writeString(body, requestTopic)
        body.write(json.toByteArray(Charsets.UTF_8))
        sendPacket(0x30, body.toByteArray())
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { sendPacket(0xE0, ByteArray(0)) } // DISCONNECT
        runCatching { socket?.close() }
    }

    val isOpen: Boolean get() = !closed && socket?.isClosed == false

    // ------------------------------------------------------------------ internals

    private fun readLoop(input: InputStream) {
        var error: Throwable? = null
        try {
            while (!closed) {
                val (type, body) = readPacket(input)
                if (type == 3) { // PUBLISH
                    val flags = lastFlags
                    val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                    var pos = 2 + topicLen
                    if ((flags shr 1) and 3 != 0) pos += 2 // packet id when QoS > 0
                    if (pos <= body.size) onMessage(String(body, pos, body.size - pos, Charsets.UTF_8))
                }
                // CONNACK/SUBACK/PINGRESP need no handling here.
            }
        } catch (t: Throwable) {
            if (!closed) error = t
        } finally {
            val wasClosed = closed
            closed = true
            runCatching { socket?.close() }
            onClosed(if (wasClosed && error == null) null else error)
        }
    }

    private fun pingLoop() {
        while (!closed) {
            try {
                Thread.sleep(30_000)
            } catch (_: InterruptedException) {
                return
            }
            if (closed) return
            runCatching { sendPacket(0xC0, ByteArray(0)) }.onFailure { runCatching { socket?.close() } }
        }
    }

    private fun connectBody(): ByteArray {
        val b = ByteArrayOutputStream()
        writeString(b, "MQTT")
        b.write(4) // protocol level 3.1.1
        b.write(0xC2) // username + password + clean session
        b.write(0); b.write(60) // keep-alive seconds
        writeString(b, clientId)
        writeString(b, "bblp")
        writeString(b, accessCode)
        return b.toByteArray()
    }

    private fun sendPacket(header: Int, body: ByteArray) {
        val o = out ?: throw IOException("Not connected")
        val packet = ByteArrayOutputStream(body.size + 5)
        packet.write(header)
        var len = body.size
        do {
            var digit = len % 128
            len /= 128
            if (len > 0) digit = digit or 0x80
            packet.write(digit)
        } while (len > 0)
        packet.write(body)
        synchronized(writeLock) {
            o.write(packet.toByteArray())
            o.flush()
        }
    }

    @Volatile private var lastFlags = 0

    private fun readPacket(input: InputStream): Pair<Int, ByteArray> {
        val first = input.read()
        if (first < 0) throw EOFException("Connection closed by printer")
        lastFlags = first and 0x0F
        var multiplier = 1
        var len = 0
        var count = 0
        while (true) {
            val d = input.read()
            if (d < 0) throw EOFException("Connection closed by printer")
            len += (d and 0x7F) * multiplier
            if (d and 0x80 == 0) break
            multiplier *= 128
            if (++count > 3) throw IOException("Malformed packet length")
        }
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n < 0) throw EOFException("Connection closed by printer")
            off += n
        }
        return (first ushr 4) to body
    }

    private fun writeString(b: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        b.write(bytes.size shr 8)
        b.write(bytes.size and 0xFF)
        b.write(bytes)
    }

    private companion object {
        /** The printer uses a self-signed certificate on the local network, so it can't be verified. */
        fun trustAllContext(): SSLContext {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        }
    }
}
