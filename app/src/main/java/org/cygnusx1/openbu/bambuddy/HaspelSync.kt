package org.cygnusx1.openbu.bambuddy

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * A Spoolman spool that HaspelSync has assigned to an AMS slot.
 * [remainingG] / [initialG] are grams of filament (without the empty spool).
 */
data class HsSpool(
    val spoolmanId: Int,
    val vendor: String,
    val name: String,
    val material: String,
    val colorHex: String?,
    val remainingG: Double,
    val initialG: Double,
) {
    /** 0..1 of the filament left, or null when the spool's full weight isn't known. */
    val fraction: Float? get() = if (initialG > 0) (remainingG / initialG).toFloat().coerceIn(0f, 1f) else null

    val gramsLabel: String get() = "${remainingG.roundToInt()} g"

    /** Running low: under 100 g or under 10%. */
    val low: Boolean get() = remainingG < 100 || (fraction ?: 1f) < 0.1f

    /**
     * Short name for the small slot label: trademark signs and the material removed, and the
     * product line dropped when the name carries one ("PolyLite™ PLA Pro Black" -> "Pro Black").
     */
    val shortName: String
        get() {
            // "PolyTerra™ PLA Matte (Savannah Yellow)": the colour in brackets is what tells spools apart.
            Regex("\\(([^)]+)\\)").find(name)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            val hadMark = name.contains('™') || name.contains('®')
            var n = name.replace("™", "").replace("®", "")
            if (material.isNotBlank()) n = n.replace(Regex("\\b${Regex.escape(material)}\\b", RegexOption.IGNORE_CASE), "")
            val words = n.split(' ').filter { it.isNotBlank() }
            val kept = if (hadMark && words.size >= 2) words.drop(1) else words
            return kept.joinToString(" ").ifBlank { name }
        }
}

/** A printer as HaspelSync knows it: [id] is the serial number. */
data class HsPrinter(val id: String, val name: String)

/**
 * Read-only client for HaspelSync (github.com/Rdiger-36/HaspelSync), the tool that links AMS slots
 * to Spoolman spools. BamMate only reads the assignments; assigning stays in HaspelSync.
 */
class HaspelSyncClient(baseUrl: String, private val apiKey: String) {
    private val base = normalize(baseUrl)

    private fun get(path: String): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("X-API-Key", apiKey)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 401 || code == 403) throw IOException("HaspelSync rejected the API key")
            if (code !in 200..299) throw IOException("HaspelSync answered $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun printers(): List<HsPrinter> = parsePrinters(get("/api/printers"))

    /** Slot label ("A1", "HT-A", "External", ...) to the spool assigned there. */
    fun spools(printerId: String): Map<String, HsSpool> = parseSpools(get("/api/spools/$printerId"))

    companion object {
        /** Adds http:// and HaspelSync's default port 4000 when they're left out. */
        fun normalize(url: String): String {
            var u = url.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            val hostPart = u.substringAfter("://").substringBefore('/')
            if (!hostPart.contains(':')) u = u.replaceFirst(hostPart, "$hostPart:4000")
            return u
        }

        fun parsePrinters(body: String): List<HsPrinter> {
            val arr = JSONArray(body.trim())
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                HsPrinter(id, o.optString("name"))
            }
        }

        fun parseSpools(body: String): Map<String, HsSpool> {
            val arr = JSONArray(body.trim())
            val out = LinkedHashMap<String, HsSpool>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("amsId")
                if (label.isBlank()) continue
                val spool = o.optJSONObject("existingSpool") ?: continue
                if (spool.optBoolean("archived", false)) continue
                val filament = spool.optJSONObject("filament") ?: JSONObject()
                out[label] = HsSpool(
                    spoolmanId = spool.optInt("id"),
                    vendor = filament.optJSONObject("vendor")?.optString("name").orEmpty()
                        .ifBlank { o.optString("vendor").takeIf { it != "null" }.orEmpty() },
                    name = filament.optString("name").ifBlank { o.optString("filamentName") },
                    material = filament.optString("material").ifBlank { o.optString("material") },
                    colorHex = filament.optString("color_hex").takeIf { it.isNotBlank() && it != "null" },
                    remainingG = spool.optDouble("remaining_weight", 0.0).let { if (it.isNaN()) 0.0 else it },
                    initialG = spool.optDouble("initial_weight", filament.optDouble("weight", 0.0)).let { if (it.isNaN()) 0.0 else it },
                )
            }
            return out
        }

        /** HaspelSync's label for a slot: "A1".."D4", "HT-A" for the first AMS HT, "External"/"External-2". */
        fun slotLabel(amsId: Int, slot: Int): String = when {
            amsId >= 254 -> if (slot == 0) "External" else "External-${slot + 1}"
            amsId >= 128 -> "HT-${'A' + (amsId - 128) % 26}"
            else -> "${'A' + amsId % 26}${slot + 1}"
        }

        /** Loose name match ("Princess" finds "Printcess"): same letters, at most 2 edits apart. */
        fun namesMatch(a: String, b: String): Boolean {
            val x = a.lowercase().filter { it.isLetterOrDigit() }
            val y = b.lowercase().filter { it.isLetterOrDigit() }
            if (x.isEmpty() || y.isEmpty()) return false
            if (x == y) return true
            val d = IntArray(y.length + 1) { it }
            for (i in 1..x.length) {
                var prev = d[0]
                d[0] = i
                for (j in 1..y.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (x[i - 1] == y[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[y.length] <= 2
        }
    }
}
