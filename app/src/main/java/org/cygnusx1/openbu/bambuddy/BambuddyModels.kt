package org.cygnusx1.openbu.bambuddy

import org.json.JSONArray
import org.json.JSONObject

data class BbPrinter(val id: Int, val name: String, val model: String, val serial: String = "")

/**
 * Dual-nozzle printers (H2D), as checked on a real printer:
 * - [nozzle] is the LEFT nozzle's reading and [nozzle2] the RIGHT one's;
 * - commands and [BbStatus.activeNozzle] number the nozzles the printer's way: 0 = right, 1 = left.
 * Single-nozzle printers only use [nozzle] and index 0.
 */
data class BbTemps(
    val nozzle: Double? = null,
    val nozzleTarget: Double? = null,
    val nozzle2: Double? = null,
    val nozzle2Target: Double? = null,
    val bed: Double? = null,
    val bedTarget: Double? = null,
    val chamber: Double? = null,
    val chamberTarget: Double? = null,
)

data class BbTray(
    val id: Int,
    val colorHex: String?,
    val type: String,
    val remain: Int,
    val exists: Boolean,
    /** Recommended drying temperature / hours for this filament, when Bambuddy knows them. */
    val dryingTemp: Int? = null,
    val dryingHours: Int? = null,
)

data class BbAms(
    val id: Int,
    val isHt: Boolean,
    val humidity: Int?,
    val temp: Double?,
    val trays: List<BbTray>,
    /** e.g. "ams", "n3f" (AMS 2 Pro), "n3s" (AMS HT); the original AMS has no heater. */
    val moduleType: String = "",
    /** Non-zero while the unit is drying. */
    val dryStatus: Int = 0,
    /** Drying time left in minutes (0 when not drying). */
    val dryMinutesLeft: Int = 0,
    val dryTargetTemp: Int? = null,
    val dryFilament: String? = null,
) {
    val drying: Boolean get() = dryStatus != 0 || dryMinutesLeft > 0
}

/** A tray position: [amsId] is the AMS unit (128+ for an AMS HT, 254 for the external spool), [slot] 0-3. */
data class TrayRef(val amsId: Int, val slot: Int)

/**
 * The printer's "tray_now" number: AMS unit * 4 + slot for a normal AMS, the unit id itself for an
 * AMS HT (128+), 254 for the external spool, 255 for nothing loaded.
 */
fun trayRefFromTrayNow(n: Int?): TrayRef? = when {
    n == null || n == 255 || n < 0 -> null
    n == 254 -> TrayRef(254, 0)
    n >= 128 -> TrayRef(n, 0)
    else -> TrayRef(n / 4, n % 4)
}

data class BbHms(val code: String, val description: String?, val severity: Int)

data class BbStatus(
    val id: Int,
    val name: String,
    val connected: Boolean,
    val state: String,
    val job: String,
    val progress: Double?,
    val remainingMin: Int?,
    val layer: Int?,
    val totalLayers: Int?,
    val temps: BbTemps,
    val light: Boolean,
    val speedLevel: Int,
    val partFan: Int?,
    val auxFan: Int?,
    val chamberFan: Int?,
    val ams: List<BbAms>,
    val external: List<BbTray>,
    val hms: List<BbHms>,
    val dualNozzle: Boolean,
    val supportsChamberHeater: Boolean,
    val awaitingPlateClear: Boolean,
    /** Printer's own stage name, e.g. "Heatbed preheating" (stg_cur_name). */
    val stage: String? = null,
    /** True when Bambuddy has a plate picture for the current print. */
    val hasCover: Boolean = false,
    val supportsDrying: Boolean = false,
    val supportsDryingWhilePrinting: Boolean = false,
    /** Why a directly connected printer is offline ("Can't reach the printer", ...). */
    val note: String? = null,
    /** Printer model ("X1C", "H2D", ...), used for per-model temperature limits. */
    val model: String = "",
    /** Serial number, when known (used to find the printer in HaspelSync). */
    val serial: String = "",
    /** Dual-nozzle printers: the nozzle in use, 0 = right, 1 = left (see [BbTemps]). Null when unknown. */
    val activeNozzle: Int? = null,
    /** The printer's own name for the running file ("/data/Metadata/plate_2.gcode"), direct mode only. */
    val jobFile: String? = null,
    /** The tray feeding the printer right now, when known. */
    val activeTray: TrayRef? = null,
    // Unmodified server response, shown in the app's "Raw data" dialog for troubleshooting.
    val raw: String,
)

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.doubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

/**
 * The spec only says "temperatures" is a free-form object, so match keys by meaning
 * (contains nozzle/bed/chamber, "target", "2"/"left") instead of exact names. If a key
 * is nested ({"nozzle": {"current": .., "target": ..}}) that is handled too.
 */
private fun parseTemps(o: JSONObject?): BbTemps {
    if (o == null) return BbTemps()
    var nozzle: Double? = null
    var nozzleT: Double? = null
    var nozzle2: Double? = null
    var nozzle2T: Double? = null
    var bed: Double? = null
    var bedT: Double? = null
    var chamber: Double? = null
    var chamberT: Double? = null

    fun put(kind: String, second: Boolean, target: Boolean, v: Double) {
        when (kind) {
            "nozzle" -> if (second) {
                if (target) nozzle2T = nozzle2T ?: v else nozzle2 = nozzle2 ?: v
            } else {
                if (target) nozzleT = nozzleT ?: v else nozzle = nozzle ?: v
            }
            "bed" -> if (target) bedT = bedT ?: v else bed = bed ?: v
            "chamber" -> if (target) chamberT = chamberT ?: v else chamber = chamber ?: v
        }
    }

    val skip = listOf("diameter", "type", "state", "heater", "status", "time", "name")
    val keys = o.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val k = key.lowercase()
        if (skip.any { it in k }) continue
        val kind = when {
            "nozzle" in k || "extruder" in k -> "nozzle"
            "bed" in k -> "bed"
            "chamber" in k -> "chamber"
            else -> continue
        }
        val second = "2" in k || "left" in k
        val value = o.opt(key)
        if (value is JSONObject) {
            value.doubleOrNull("current")?.let { put(kind, second, false, it) }
                ?: value.doubleOrNull("temp")?.let { put(kind, second, false, it) }
            value.doubleOrNull("target")?.let { put(kind, second, true, it) }
        } else {
            val v = o.doubleOrNull(key) ?: continue
            put(kind, second, "target" in k, v)
        }
    }
    return BbTemps(nozzle, nozzleT, nozzle2, nozzle2T, bed, bedT, chamber, chamberT)
}

private fun parseTray(o: JSONObject): BbTray = BbTray(
    id = o.optInt("id"),
    colorHex = o.stringOrNull("tray_color"),
    type = o.stringOrNull("tray_type") ?: "",
    remain = if (o.has("remain") && !o.isNull("remain")) o.optInt("remain") else -1,
    exists = if (o.has("exists") && !o.isNull("exists")) o.optBoolean("exists") else true,
    dryingTemp = o.intOrNull("drying_temp")?.takeIf { it > 0 },
    dryingHours = o.intOrNull("drying_time")?.takeIf { it > 0 },
)

private fun trays(arr: JSONArray?): List<BbTray> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseTray) }
}

fun parsePrinterList(body: String): List<BbPrinter> {
    val trimmed = body.trim()
    val arr: JSONArray = if (trimmed.startsWith("[")) {
        JSONArray(trimmed)
    } else {
        val o = JSONObject(trimmed)
        o.optJSONArray("printers") ?: o.optJSONArray("items") ?: JSONArray()
    }
    return (0 until arr.length()).mapNotNull { i ->
        val p = arr.optJSONObject(i) ?: return@mapNotNull null
        BbPrinter(
            id = p.optInt("id"),
            name = p.optString("name", "Printer ${p.optInt("id")}"),
            model = p.optString("model", ""),
            serial = p.optString("serial_number", "").ifBlank { p.optString("serial", "") },
        )
    }.sortedBy { it.id }
}

fun parseStatus(body: String): BbStatus {
    val o = JSONObject(body)

    val amsArr = o.optJSONArray("ams")
    val ams = if (amsArr == null) emptyList() else (0 until amsArr.length()).mapNotNull { i ->
        val u = amsArr.optJSONObject(i) ?: return@mapNotNull null
        BbAms(
            id = u.optInt("id"),
            isHt = u.optBoolean("is_ams_ht", false),
            humidity = u.intOrNull("humidity"),
            temp = u.doubleOrNull("temp"),
            trays = trays(u.optJSONArray("tray")),
            moduleType = u.stringOrNull("module_type") ?: "",
            dryStatus = u.optInt("dry_status", 0),
            dryMinutesLeft = u.optInt("dry_time", 0),
            dryTargetTemp = u.intOrNull("dry_target_temp")?.takeIf { it > 0 },
            dryFilament = u.stringOrNull("dry_filament"),
        )
    }

    val hmsArr = o.optJSONArray("hms_errors")
    val hms = if (hmsArr == null) emptyList() else (0 until hmsArr.length()).mapNotNull { i ->
        val h = hmsArr.optJSONObject(i) ?: return@mapNotNull null
        BbHms(
            code = h.stringOrNull("full_code") ?: h.optString("code", ""),
            description = h.stringOrNull("description"),
            severity = h.optInt("severity"),
        )
    }

    val temps = parseTemps(o.optJSONObject("temperatures"))

    return BbStatus(
        id = o.optInt("id"),
        name = o.optString("name", ""),
        connected = o.optBoolean("connected", false),
        state = (o.stringOrNull("state") ?: "").uppercase(),
        job = o.stringOrNull("subtask_name") ?: o.stringOrNull("current_print")
            ?: o.stringOrNull("gcode_file") ?: "",
        progress = o.doubleOrNull("progress"),
        remainingMin = o.intOrNull("remaining_time"),
        layer = o.intOrNull("layer_num"),
        totalLayers = o.intOrNull("total_layers"),
        temps = temps,
        light = o.optBoolean("chamber_light", false),
        speedLevel = o.optInt("speed_level", 2),
        partFan = o.intOrNull("cooling_fan_speed"),
        auxFan = o.intOrNull("big_fan1_speed"),
        chamberFan = o.intOrNull("big_fan2_speed"),
        ams = ams,
        external = trays(o.optJSONArray("vt_tray")),
        hms = hms,
        // The nozzles array can list two entries on single-nozzle printers (X1C), so only a
        // second nozzle *temperature* proves a dual-nozzle machine (H2D).
        dualNozzle = temps.nozzle2 != null,
        supportsChamberHeater = o.optBoolean("supports_chamber_heater", false),
        awaitingPlateClear = o.optBoolean("awaiting_plate_clear", false),
        stage = o.stringOrNull("stg_cur_name"),
        hasCover = o.stringOrNull("cover_url") != null,
        supportsDrying = o.optBoolean("supports_drying", false),
        supportsDryingWhilePrinting = o.optBoolean("supports_drying_while_printing", false),
        activeNozzle = o.intOrNull("active_extruder") ?: o.intOrNull("active_nozzle"),
        activeTray = trayRefFromTrayNow(
            o.intOrNull("tray_now") ?: o.optJSONObject("ams_status")?.intOrNull("tray_now"),
        ),
        raw = body,
    )
}

/**
 * An object on the plate. [x]/[y] is its centre in mm (from Bambuddy); [bbox] its outline
 * (minX, minY, maxX, maxY in mm) when known (direct mode reads it from the print file).
 */
data class BbObject(
    val id: Int,
    val name: String,
    val skipped: Boolean,
    val x: Double? = null,
    val y: Double? = null,
    val bbox: List<Double>? = null,
)

data class BbObjects(val objects: List<BbObject>, val raw: String)

/**
 * The spec only says the response lists objects with an id, a name, a position and a skip
 * status, plus a skipped_objects list, so accept a bare array or an object holding "objects",
 * and several spellings of the id. The raw text is kept so the dialog can show it if nothing parses.
 */
fun parseObjects(body: String): BbObjects {
    val trimmed = body.trim()
    val root: JSONObject? = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
    val arr: JSONArray = when {
        trimmed.startsWith("[") -> JSONArray(trimmed)
        root != null -> root.optJSONArray("objects") ?: root.optJSONArray("items") ?: JSONArray()
        else -> JSONArray()
    }
    val skippedIds = mutableSetOf<Int>()
    root?.optJSONArray("skipped_objects")?.let { sk ->
        for (i in 0 until sk.length()) {
            val v = sk.opt(i)
            when (v) {
                is Number -> skippedIds += v.toInt()
                is JSONObject -> v.intOrNull("id")?.let { skippedIds += it }
            }
        }
    }
    val list = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.intOrNull("id") ?: o.intOrNull("identify_id") ?: o.intOrNull("object_id")
            ?: return@mapNotNull null
        BbObject(
            id = id,
            name = o.stringOrNull("name") ?: "Object $id",
            skipped = o.optBoolean("skipped", false) || id in skippedIds,
            x = o.optDouble("x").takeIf { !it.isNaN() },
            y = o.optDouble("y").takeIf { !it.isNaN() },
        )
    }
    return BbObjects(list, body)
}
