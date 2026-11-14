package org.cygnusx1.openbu.direct

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Minimal RTSP-over-TLS client for the camera of X1 and H2 printers
 * (rtsps://bblp:<access code>@<ip>:322/streaming/live/1).
 *
 * It asks for the stream over the same TCP connection (interleaved RTP), unpacks the H.264
 * video from the RTP packets and hands out whole frames as Annex-B byte streams (each NAL unit
 * with a 00 00 00 01 start code), ready for Android's video decoder.
 *
 * Blocking; [run] returns when the stream ends and throws on errors. [stop] ends it from another
 * thread.
 */
class RtspClient(
    private val host: String,
    private val accessCode: String,
    private val port: Int = 322,
    private val path: String = "/streaming/live/1",
) {
    /** Called once with the stream's SPS and PPS (without start codes), when known. */
    var onConfig: (sps: ByteArray, pps: ByteArray) -> Unit = { _, _ -> }

    /** Called for every complete frame: Annex-B bytes, presentation time in microseconds, key frame. */
    var onFrame: (data: ByteArray, timeUs: Long, keyFrame: Boolean) -> Unit = { _, _, _ -> }

    @Volatile private var socket: Socket? = null
    @Volatile private var stopped = false
    private var cseq = 0
    private var auth: String? = null
    private var digest: Map<String, String>? = null

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
    }

    fun run() {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 8_000)
        val s = trustAll().socketFactory.createSocket(raw, host, port, true) as SSLSocket
        socket = s
        if (stopped) {
            s.close()
            return
        }
        s.soTimeout = 15_000
        s.startHandshake()
        val input = BufferedInputStream(s.inputStream, 64 * 1024)
        val out = s.outputStream
        val url = "rtsps://$host:$port$path"

        try {
            // DESCRIBE (answering the login challenge if there is one) gives the stream description.
            var r = request(input, out, "DESCRIBE", url, mapOf("Accept" to "application/sdp"))
            if (r.code == 401) {
                prepareAuth(r.headers["www-authenticate"] ?: throw IOException("The camera asked for a login it didn't describe"))
                r = request(input, out, "DESCRIBE", url, mapOf("Accept" to "application/sdp"))
            }
            if (r.code == 401) throw IOException("The camera rejected the access code")
            if (r.code != 200) throw IOException("Camera: ${r.status}")

            val base = (r.headers["content-base"] ?: r.headers["content-location"] ?: "$url/").let { if (it.endsWith("/")) it else "$it/" }
            val sdp = parseSdp(r.body)
            sdp.sps?.let { sps -> sdp.pps?.let { pps -> onConfig(sps, pps) } }
            val track = when {
                sdp.control == null || sdp.control == "*" -> url
                sdp.control.startsWith("rtsp") -> sdp.control
                else -> base + sdp.control
            }

            r = request(input, out, "SETUP", track, mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"))
            if (r.code != 200) throw IOException("Camera setup failed: ${r.status}")
            val session = r.headers["session"]?.substringBefore(';')?.trim() ?: throw IOException("The camera gave no session")
            val channel = Regex("interleaved=(\\d+)").find(r.headers["transport"] ?: "")?.groupValues?.get(1)?.toInt() ?: 0

            r = request(input, out, "PLAY", url, mapOf("Session" to session, "Range" to "npt=0.000-"))
            if (r.code != 200) throw IOException("Camera play failed: ${r.status}")

            s.soTimeout = 20_000
            readStream(input, out, url, session, channel, sdp.payloadType)
        } catch (e: Exception) {
            if (!stopped) throw e
        } finally {
            runCatching { s.close() }
        }
    }

    // ------------------------------------------------------------------ RTSP messages

    private class Response(val code: Int, val status: String, val headers: Map<String, String>, val body: String)

    private fun request(input: InputStream, out: OutputStream, method: String, url: String, extra: Map<String, String>): Response {
        sendRequest(out, method, url, extra)
        while (true) {
            // Skip any interleaved packets that arrive before the reply.
            val first = peek(input)
            if (first == '$'.code) {
                skipInterleaved(input)
                continue
            }
            return readResponse(input)
        }
    }

    private fun sendRequest(out: OutputStream, method: String, url: String, extra: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("$method $url RTSP/1.0\r\n")
        sb.append("CSeq: ${++cseq}\r\n")
        sb.append("User-Agent: BamMate\r\n")
        authorization(method, url)?.let { sb.append("Authorization: $it\r\n") }
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        synchronized(out) {
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun readResponse(input: InputStream): Response {
        val status = readLine(input)
        if (!status.startsWith("RTSP/")) throw IOException("Unexpected reply from camera: $status")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Unexpected reply: $status")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) {
                val k = line.substring(0, i).trim().lowercase()
                // Several WWW-Authenticate lines are possible; keep the first Digest one, else the first.
                if (k == "www-authenticate" && headers.containsKey(k) && !line.contains("Digest", true)) continue
                if (k == "www-authenticate" && headers[k]?.startsWith("Digest", true) == true) continue
                headers[k] = line.substring(i + 1).trim()
            }
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        readFully(input, body)
        return Response(code, status, headers, String(body, Charsets.UTF_8))
    }

    // ------------------------------------------------------------------ login

    private fun prepareAuth(challenge: String) {
        when {
            challenge.startsWith("Digest", ignoreCase = true) -> {
                // realm="...", nonce="..." (quotes optional)
                digest = Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))").findAll(challenge.removeRange(0, 6)).associate {
                    it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] }
                }
                auth = null
            }
            else -> {
                auth = "Basic " + Base64.getEncoder().encodeToString("bblp:$accessCode".toByteArray(Charsets.UTF_8))
                digest = null
            }
        }
    }

    private fun authorization(method: String, url: String): String? {
        auth?.let { return it }
        val d = digest ?: return null
        val realm = d["realm"] ?: ""
        val nonce = d["nonce"] ?: ""
        val ha1 = md5("bblp:$realm:$accessCode")
        val ha2 = md5("$method:$url")
        val response = md5("$ha1:$nonce:$ha2")
        return "Digest username=\"bblp\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$url\", response=\"$response\""
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ SDP

    private class Sdp(val control: String?, val payloadType: Int, val sps: ByteArray?, val pps: ByteArray?)

    private fun parseSdp(body: String): Sdp {
        var inVideo = false
        var control: String? = null
        var pt = 96
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (raw in body.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("m=") -> {
                    inVideo = line.startsWith("m=video")
                    if (inVideo) line.split(' ').getOrNull(3)?.toIntOrNull()?.let { pt = it }
                }
                inVideo && line.startsWith("a=control:") -> control = line.removePrefix("a=control:").trim()
                inVideo && line.startsWith("a=fmtp:") -> {
                    Regex("sprop-parameter-sets=([^;\\s]+)").find(line)?.groupValues?.get(1)?.split(',')?.let { sets ->
                        val dec = Base64.getDecoder()
                        sps = sets.getOrNull(0)?.let { runCatching { dec.decode(it) }.getOrNull() }
                        pps = sets.getOrNull(1)?.let { runCatching { dec.decode(it) }.getOrNull() }
                    }
                }
            }
        }
        return Sdp(control, pt, sps, pps)
    }

    // ------------------------------------------------------------------ RTP / H.264

    private fun readStream(input: InputStream, out: OutputStream, url: String, session: String, channel: Int, pt: Int) {
        val frame = ByteArrayOutputStream(256 * 1024)
        var frameTs = -1L
        var key = false
        var firstTs = -1L
        var fu: ByteArrayOutputStream? = null
        var lastKeepAlive = System.currentTimeMillis()
        var sentConfig = false
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        fun addNal(nal: ByteArray) {
            if (nal.isEmpty()) return
            val type = nal[0].toInt() and 0x1F
            when (type) {
                7 -> sps = nal
                8 -> pps = nal
                5 -> key = true
            }
            if (!sentConfig && sps != null && pps != null) {
                sentConfig = true
                onConfig(sps!!, pps!!)
            }
            frame.write(START)
            frame.write(nal)
        }

        fun flush() {
            if (frame.size() > 0 && frameTs >= 0) {
                if (firstTs < 0) firstTs = frameTs
                onFrame(frame.toByteArray(), (frameTs - firstTs) * 1_000_000L / 90_000L, key)
            }
            frame.reset()
            key = false
        }

        while (!stopped) {
            // Keep the session alive (servers drop it after about a minute of silence).
            if (System.currentTimeMillis() - lastKeepAlive > 25_000) {
                sendRequest(out, "GET_PARAMETER", url, mapOf("Session" to session))
                lastKeepAlive = System.currentTimeMillis()
            }
            val b = input.read()
            if (b < 0) throw EOFException("The camera stream ended")
            if (b != '$'.code) {
                // An RTSP reply (to the keep-alive) in between the video: read and ignore it.
                skipResponseAfterFirstByte(input)
                continue
            }
            val ch = input.read()
            val hi = input.read()
            val lo = input.read()
            if (lo < 0) throw EOFException("The camera stream ended")
            val len = (hi shl 8) or lo
            val pkt = ByteArray(len)
            readFully(input, pkt)
            if (ch != channel || len < 12) continue // RTCP or something else

            val cc = pkt[0].toInt() and 0x0F
            val hasExt = pkt[0].toInt() and 0x10 != 0
            val marker = pkt[1].toInt() and 0x80 != 0
            val payloadType = pkt[1].toInt() and 0x7F
            if (payloadType != pt) continue
            val ts = ((pkt[4].toLong() and 0xFF) shl 24) or ((pkt[5].toLong() and 0xFF) shl 16) or
                ((pkt[6].toLong() and 0xFF) shl 8) or (pkt[7].toLong() and 0xFF)
            var off = 12 + cc * 4
            if (hasExt && off + 4 <= len) {
                val extLen = ((pkt[off + 2].toInt() and 0xFF) shl 8) or (pkt[off + 3].toInt() and 0xFF)
                off += 4 + extLen * 4
            }
            var end = len
            if (pkt[0].toInt() and 0x20 != 0) end -= pkt[len - 1].toInt() and 0xFF // padding
            if (off >= end) continue

            if (frameTs >= 0 && ts != frameTs) flush() // a new frame started without a marker
            frameTs = ts

            val nalType = pkt[off].toInt() and 0x1F
            when (nalType) {
                in 1..23 -> addNal(pkt.copyOfRange(off, end))
                24 -> { // STAP-A: several NAL units, each with a 2-byte size
                    var p = off + 1
                    while (p + 2 <= end) {
                        val size = ((pkt[p].toInt() and 0xFF) shl 8) or (pkt[p + 1].toInt() and 0xFF)
                        p += 2
                        if (size <= 0 || p + size > end) break
                        addNal(pkt.copyOfRange(p, p + size))
                        p += size
                    }
                }
                28 -> { // FU-A: one NAL unit split over several packets
                    if (off + 2 > end) continue
                    val indicator = pkt[off].toInt() and 0xFF
                    val header = pkt[off + 1].toInt() and 0xFF
                    val start = header and 0x80 != 0
                    val stop = header and 0x40 != 0
                    if (start) {
                        fu = ByteArrayOutputStream(64 * 1024).also {
                            it.write((indicator and 0xE0) or (header and 0x1F))
                        }
                    }
                    fu?.write(pkt, off + 2, end - off - 2)
                    if (stop) {
                        fu?.let { addNal(it.toByteArray()) }
                        fu = null
                    }
                }
            }
            if (marker) flush()
        }
    }

    /** Reads (and drops) an RTSP reply whose first byte was already read. */
    private fun skipResponseAfterFirstByte(input: InputStream) {
        readLine(input) // rest of the status line
        var len = 0
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) len = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (len > 0) readFully(input, ByteArray(len))
    }

    private fun peek(input: InputStream): Int {
        input.mark(1)
        val b = input.read()
        input.reset()
        if (b < 0) throw EOFException("The camera closed the connection")
        return b
    }

    private fun skipInterleaved(input: InputStream) {
        input.read() // '$'
        input.read() // channel
        val hi = input.read()
        val lo = input.read()
        if (lo < 0) throw EOFException("The camera closed the connection")
        readFully(input, ByteArray((hi shl 8) or lo))
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("The camera closed the connection")
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("Unexpected data from camera")
        }
    }

    private fun readFully(input: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) throw EOFException("The camera closed the connection")
            off += n
        }
    }

    companion object {
        private val START = byteArrayOf(0, 0, 0, 1)

        private fun trustAll(): SSLContext {
            // The printer uses a self-signed certificate on the local network.
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        }
    }
}
