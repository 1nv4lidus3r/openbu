package org.cygnusx1.openbu.bambuddy

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class BambuddyException(val httpCode: Int, message: String) : IOException(message)

/**
 * Minimal REST client for a Bambuddy server (https://wiki.bambuddy.cool/reference/api/).
 * Uses X-API-Key auth and plain HttpURLConnection, so no new dependencies are needed.
 * All methods block; call them from Dispatchers.IO.
 */
class BambuddyClient(baseUrl: String, private val apiKey: String) {

    private val base: String = run {
        var u = baseUrl.trim().trimEnd('/')
        if (!u.contains("://")) u = "http://$u"
        if (!u.endsWith("/api/v1")) u += "/api/v1"
        u
    }

    private fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
    ): String {
        val qs = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val conn = URL(base + path + qs).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method // PATCH is supported by Android's built-in HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) conn.setRequestProperty("X-API-Key", apiKey)
            if (method == "POST" || jsonBody != null) {
                conn.doOutput = true
                if (jsonBody == null) {
                    conn.setFixedLengthStreamingMode(0)
                    conn.outputStream.close()
                } else {
                    val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw BambuddyException(code, errorMessage(code, text))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).opt("detail")?.toString()
        } catch (_: Exception) {
            null
        }
        val hint = when (code) {
            401 -> " (API key missing or wrong)"
            403 -> " (the API key lacks permission for this action)"
            else -> ""
        }
        return "HTTP $code${if (detail.isNullOrBlank()) "" else ": $detail"}$hint"
    }

    fun listPrinters(): List<BbPrinter> = parsePrinterList(request("GET", "/printers/"))

    fun status(printerId: Int): BbStatus = parseStatus(request("GET", "/printers/$printerId/status"))

    // --- controls (all take query parameters, per the OpenAPI spec) ---

    fun setBedTemp(id: Int, target: Int) {
        request("POST", "/printers/$id/temperature/bed", mapOf("target" to target.coerceIn(0, 140).toString()))
    }

    /** nozzle: 0 = right/default, 1 = left (dual-nozzle printers). */
    fun setNozzleTemp(id: Int, target: Int, nozzle: Int = 0) {
        request(
            "POST", "/printers/$id/temperature/nozzle",
            mapOf("target" to target.coerceIn(0, 320).toString(), "nozzle" to nozzle.toString()),
        )
    }

    fun setChamberTemp(id: Int, target: Int) {
        request("POST", "/printers/$id/temperature/chamber", mapOf("target" to target.coerceIn(0, 65).toString()))
    }

    /** fan: "part", "aux" or "chamber"; speed in percent. */
    fun setFanSpeed(id: Int, fan: String, percent: Int) {
        request(
            "POST", "/printers/$id/fan-speed",
            mapOf("fan" to fan, "speed" to percent.coerceIn(0, 100).toString()),
        )
    }

    /** mode: 1 silent, 2 standard, 3 sport, 4 ludicrous. */
    fun setPrintSpeed(id: Int, mode: Int) {
        request("POST", "/printers/$id/print-speed", mapOf("mode" to mode.coerceIn(1, 4).toString()))
    }

    fun setChamberLight(id: Int, on: Boolean) {
        request("POST", "/printers/$id/chamber-light", mapOf("on" to on.toString()))
    }

    fun pause(id: Int) { request("POST", "/printers/$id/print/pause") }

    fun resume(id: Int) { request("POST", "/printers/$id/print/resume") }

    fun stop(id: Int) { request("POST", "/printers/$id/print/stop") }

    /** Tell Bambuddy the build plate has been cleared, so its queue may start the next job. */
    fun clearPlate(id: Int) { request("POST", "/printers/$id/clear-plate") }

    // --- skip objects ---

    /**
     * reload = true makes Bambuddy re-read the objects from the current print's file instead of
     * returning its cached list, which can be left over from an earlier print.
     */
    fun printObjects(id: Int, reload: Boolean = true): BbObjects =
        parseObjects(request("GET", "/printers/$id/print/objects", mapOf("reload" to reload.toString())))

    /** Skips the given objects (their identify ids) for the current print. Cannot be undone. */
    fun skipObjects(id: Int, objectIds: List<Int>) {
        request("POST", "/printers/$id/print/skip-objects", jsonBody = objectIds.joinToString(prefix = "[", postfix = "]"))
    }

    // --- maintenance & stats ---

    fun maintenanceOverview(): List<BbMaintPrinter> = parseMaintOverview(request("GET", "/maintenance/overview"))

    /** Record a maintenance item as done now (resets its counter). */
    fun performMaintenance(itemId: Int) {
        // The body is required (PerformMaintenanceRequest); notes are optional.
        request("POST", "/maintenance/items/$itemId/perform", jsonBody = "{}")
    }

    /** Print statistics from dateFrom (yyyy-MM-dd, null = all time) until today. */
    fun printStats(dateFrom: String?, tz: String): BbStats {
        val q = mutableMapOf("tz" to tz)
        if (dateFrom != null) q["date_from"] = dateFrom
        return parseStats(request("GET", "/archives/stats", q))
    }

    // --- smart plug (power) ---

    /** The main plug assigned to a printer, or null if it has none. */
    fun plugForPrinter(printerId: Int): BbPlug? = try {
        parsePlug(request("GET", "/smart-plugs/by-printer/$printerId"))
    } catch (e: BambuddyException) {
        if (e.httpCode == 404) null else throw e
    }

    fun plugStatus(plugId: Int): BbPlugStatus = parsePlugStatus(request("GET", "/smart-plugs/$plugId/status"))

    /** action: "on", "off" or "toggle". */
    fun controlPlug(plugId: Int, action: String) {
        request("POST", "/smart-plugs/$plugId/control", jsonBody = JSONObject().put("action", action).toString())
    }

    fun setPlugAutoOff(plugId: Int, enabled: Boolean) {
        request("PATCH", "/smart-plugs/$plugId", jsonBody = JSONObject().put("auto_off", enabled).toString())
    }

    // --- files on the printer ---

    fun listFiles(id: Int, path: String): String =
        request("GET", "/printers/$id/files", mapOf("path" to path))

    fun storage(id: Int): String = request("GET", "/printers/$id/storage")

    fun deleteFile(id: Int, path: String) {
        request("DELETE", "/printers/$id/files", mapOf("path" to path))
    }

    /** Plates in a .3mf on the printer (raw JSON; see parsePlateIndexes). */
    fun filePlates(id: Int, path: String): String =
        request("GET", "/printers/$id/files/plates", mapOf("path" to path))

    /** Opens a file download. The caller reads and closes it. */
    fun openFileDownload(id: Int, path: String): HttpURLConnection =
        open("/printers/$id/files/download?path=" + URLEncoder.encode(path, "UTF-8"), readTimeoutMs = 60_000)

    fun openPlateThumbnail(id: Int, path: String, plateIndex: Int, token: String): HttpURLConnection =
        open(
            "/printers/$id/files/plate-thumbnail/$plateIndex?path=" + URLEncoder.encode(path, "UTF-8") +
                "&token=" + URLEncoder.encode(token, "UTF-8"),
        )

    private fun open(pathAndQuery: String, readTimeoutMs: Int = 20_000): HttpURLConnection {
        val conn = URL(base + pathAndQuery).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = readTimeoutMs
        if (apiKey.isNotBlank()) conn.setRequestProperty("X-API-Key", apiKey)
        return conn
    }

    /** Plate picture of the print that is on the printer now. */
    fun openPrintCover(id: Int, token: String): HttpURLConnection =
        open("/printers/$id/cover?token=" + URLEncoder.encode(token, "UTF-8"))

    // --- camera ---

    /** Short-lived token (60 min) that the camera endpoints need instead of the API key header. */
    fun cameraToken(): String {
        val body = request("POST", "/printers/camera/stream-token")
        val o = JSONObject(body)
        return o.optString("token").ifBlank { o.optString("access_token") }
            .ifBlank { throw BambuddyException(0, "The server did not return a camera token") }
    }

    /** Opens the live MJPEG stream. The caller reads and closes it. */
    fun openCameraStream(id: Int, token: String, viewer: String, fps: Int = 8): HttpURLConnection {
        val qs = "fps=$fps&viewer=" + URLEncoder.encode(viewer, "UTF-8") +
            "&token=" + URLEncoder.encode(token, "UTF-8")
        val conn = URL("$base/printers/$id/camera/stream?$qs").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 20_000
        if (apiKey.isNotBlank()) conn.setRequestProperty("X-API-Key", apiKey)
        return conn
    }

    /** Tells the server this viewer is gone so it can stop pulling video from the printer. */
    fun stopCamera(id: Int, viewer: String) {
        request("POST", "/printers/$id/camera/stop", mapOf("viewer" to viewer))
    }
}
