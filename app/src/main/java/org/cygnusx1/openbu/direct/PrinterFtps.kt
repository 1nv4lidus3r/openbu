package org.cygnusx1.openbu.direct

import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneId
import java.util.Locale
import org.conscrypt.Conscrypt
import org.conscrypt.SSLClientSessionCache
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** One entry of a directory listing on the printer. [time] is epoch millis when it could be read. */
data class FtpEntry(val name: String, val isDir: Boolean, val size: Long?, val time: Long?)

/**
 * Minimal FTPS client for a Bambu printer's storage (implicit TLS on port 990, user "bblp",
 * password = access code), as used by Bambu Studio for uploads and timelapses.
 *
 * The printer insists that each data connection resumes the TLS session of the control connection
 * ("522 session reuse required"). Java looks sessions up by host and port, which differ for the
 * data connection, so BamMate uses Conscrypt with a one-entry session cache that always hands
 * back the control connection's session. TLS 1.2 is used because its session is ready right after
 * the handshake (TLS 1.3 delivers it later, often too late for the data connection).
 *
 * One control connection per operation keeps things simple and robust; the printer is on the LAN.
 */
class PrinterFtps(
    private val host: String,
    private val accessCode: String,
    private val port: Int = 990,
) {
    private val ssl: SSLContext = sslContext()

    private class Control(val socket: SSLSocket, val reader: BufferedReader, val out: OutputStream)

    private data class Reply(val code: Int, val text: String)

    private fun open(): Control {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 8_000)
        val s = ssl.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.useTls12()
        s.soTimeout = 20_000
        s.startHandshake()
        val c = Control(s, BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8)), s.outputStream)
        expect(c, read(c), 220)
        val user = command(c, "USER bblp")
        if (user.code == 331) {
            val pass = command(c, "PASS $accessCode")
            if (pass.code != 230) throw IOException("The printer rejected the access code")
        } else {
            expect(c, user, 230)
        }
        command(c, "PBSZ 0")
        expect(c, command(c, "PROT P"), 200)
        expect(c, command(c, "TYPE I"), 200)
        return c
    }

    private fun read(c: Control): Reply {
        val first = c.reader.readLine() ?: throw IOException("The printer closed the connection")
        if (first.length < 3) throw IOException("Unexpected reply: $first")
        val code = first.substring(0, 3).toIntOrNull() ?: throw IOException("Unexpected reply: $first")
        val sb = StringBuilder(first)
        // Multi-line reply: "123-..." until a line starting with "123 ".
        if (first.length > 3 && first[3] == '-') {
            while (true) {
                val line = c.reader.readLine() ?: break
                sb.append('\n').append(line)
                if (line.startsWith("$code ")) break
            }
        }
        return Reply(code, sb.toString())
    }

    private fun command(c: Control, cmd: String): Reply {
        c.out.write((cmd + "\r\n").toByteArray(Charsets.UTF_8))
        c.out.flush()
        return read(c)
    }

    private fun expect(c: Control, r: Reply, vararg ok: Int) {
        if (r.code !in ok) {
            runCatching { c.socket.close() }
            throw IOException("Printer storage: ${r.text.lineSequence().first()}")
        }
    }

    private fun close(c: Control) {
        runCatching { command(c, "QUIT") }
        runCatching { c.socket.close() }
    }

    /** Opens a passive data connection, TLS-wrapped so it resumes the control session. */
    private fun data(c: Control): SSLSocket {
        val r = command(c, "PASV")
        expect(c, r, 227)
        val nums = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(r.text)?.groupValues
            ?: throw IOException("Unexpected passive reply")
        val dataPort = nums[5].toInt() * 256 + nums[6].toInt()
        val raw = Socket()
        // The printer may announce an internal address; the control host is the one we can reach.
        raw.connect(InetSocketAddress(host, dataPort), 8_000)
        val s = ssl.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.useTls12()
        s.soTimeout = 30_000
        return s
    }

    /** Lists a directory ("/", "/cache", ...). Folders first, then by name. */
    fun list(path: String): List<FtpEntry> {
        val c = open()
        try {
            val d = data(c)
            val r = command(c, "LIST ${path.ifBlank { "/" }}")
            expect(c, r, 125, 150)
            val text = d.use {
                it.startHandshake()
                it.inputStream.bufferedReader().readText()
            }
            expect(c, read(c), 226, 250)
            return text.lineSequence().mapNotNull { parseListLine(it) }
                .filter { it.name != "." && it.name != ".." }
                .sortedWith(compareByDescending<FtpEntry> { it.isDir }.thenBy { it.name.lowercase() })
                .toList()
        } finally {
            close(c)
        }
    }

    /** Deletes a file, or an (empty) folder when [isDir]. */
    fun delete(path: String, isDir: Boolean) {
        val c = open()
        try {
            expect(c, command(c, (if (isDir) "RMD " else "DELE ") + path), 250, 200)
        } finally {
            close(c)
        }
    }

    /**
     * Opens a file for reading. The caller must close the stream; closing finishes the transfer
     * and the control connection. [size] is the file size when the printer reports it.
     */
    fun download(path: String): Pair<InputStream, Long?> {
        val c = open()
        try {
            val size = command(c, "SIZE $path").takeIf { it.code == 213 }?.text?.substring(4)?.trim()?.toLongOrNull()
            val d = data(c)
            val r = command(c, "RETR $path")
            expect(c, r, 125, 150)
            d.startHandshake()
            val stream = object : FilterInputStream(d.inputStream) {
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    runCatching { super.close() }
                    runCatching { d.close() }
                    // 226 when the whole file was read, 426 when we stopped early (thumbnails).
                    runCatching { c.socket.soTimeout = 3_000 }
                    runCatching { read(c) }
                    close(c)
                }
            }
            return stream to size
        } catch (e: Exception) {
            close(c)
            throw e
        }
    }

    /** Remembers the newest session and offers it for every connection, whatever its port. */
    private class OneSessionCache : SSLClientSessionCache {
        @Volatile private var data: ByteArray? = null
        override fun getSessionData(host: String, port: Int): ByteArray? = data
        override fun putSessionData(session: SSLSession, sessionData: ByteArray?) {
            if (sessionData != null) data = sessionData
        }
    }

    private fun sslContext(): SSLContext {
        // The printer uses a self-signed certificate on the local network.
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val provider = runCatching { Conscrypt.newProvider() }.getOrNull()
        val ctx = if (provider != null) SSLContext.getInstance("TLS", provider) else SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        if (provider != null) Conscrypt.setClientSessionCache(ctx, OneSessionCache())
        return ctx
    }

    private fun SSLSocket.useTls12() {
        if ("TLSv1.2" in supportedProtocols) enabledProtocols = arrayOf("TLSv1.2")
    }

    companion object {

        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        /**
         * Parses one line of a Unix-style listing:
         * "-rw-r--r--   1 root  root   1234567 Nov 12 22:15 plate_1.gcode.3mf"
         * "drwxr-xr-x   2 root  root      4096 Mar  3  2025 timelapse"
         */
        fun parseListLine(line: String): FtpEntry? {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("total")) return null
            val parts = t.split(Regex("\\s+"), limit = 9)
            if (parts.size < 9) return null
            val isDir = parts[0].startsWith("d")
            val size = parts[4].toLongOrNull()
            val month = MONTHS.indexOf(parts[5].lowercase(Locale.US).take(3)) + 1
            val day = parts[6].toIntOrNull()
            val time = if (month > 0 && day != null) {
                runCatching {
                    val yt = parts[7]
                    val dt = if (yt.contains(':')) {
                        val (h, m) = yt.split(':').map { it.toInt() }
                        var d = LocalDateTime.of(Year.now().value, month, day, h, m)
                        // Without a year the date is within the last 6 months.
                        if (d.isAfter(LocalDateTime.now().plusDays(1))) d = d.minusYears(1)
                        d
                    } else {
                        LocalDateTime.of(yt.toInt(), month, day, 0, 0)
                    }
                    dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            } else {
                null
            }
            return FtpEntry(parts[8], isDir, if (isDir) null else size, time)
        }
    }
}
