package org.cygnusx1.openbu.direct

import org.cygnusx1.openbu.bambuddy.BbAms
import org.cygnusx1.openbu.bambuddy.BbHms
import org.cygnusx1.openbu.bambuddy.BbStatus
import org.cygnusx1.openbu.bambuddy.BbTemps
import org.cygnusx1.openbu.bambuddy.BbTray
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The printer's state as built up from its MQTT reports.
 *
 * After a "pushall" the printer sends its full state, then mostly partial updates that only
 * contain what changed (P1/A1 printers always send partial updates). Each report is merged into
 * the accumulated state: objects are merged key by key, and arrays of objects that carry an "id"
 * are merged entry by entry (AMS units and trays), so a partial update never wipes out the rest.
 */
class DirectState {
    private val root = JSONObject()

    @Synchronized
    fun merge(reportJson: String) {
        val report = try {
            JSONObject(reportJson)
        } catch (_: Exception) {
            return
        }
        mergeInto(root, report)
    }

    @Synchronized
    fun snapshot(): JSONObject = JSONObject(root.toString())

    @Synchronized
    fun isEmpty(): Boolean = root.optJSONObject("print") == null

    private fun mergeInto(target: JSONObject, src: JSONObject) {
        for (key in src.keys()) {
            val v = src.get(key)
            val cur = target.opt(key)
            when {
                v is JSONObject && cur is JSONObject -> mergeInto(cur, v)
                v is JSONArray && cur is JSONArray && isIdArray(v) && isIdArray(cur) -> mergeById(cur, v)
                else -> target.put(key, v)
            }
        }
    }

    private fun isIdArray(a: JSONArray): Boolean =
        a.length() > 0 && (0 until a.length()).all { a.optJSONObject(it)?.has("id") == true }

    private fun mergeById(target: JSONArray, src: JSONArray) {
        for (i in 0 until src.length()) {
            val item = src.getJSONObject(i)
            val id = item.opt("id")?.toString()
            val existing = (0 until target.length()).map { target.getJSONObject(it) }.firstOrNull { it.opt("id")?.toString() == id }
            if (existing != null) mergeInto(existing, item) else target.put(item)
        }
    }
}

/** Printer model from the serial number prefix (as printed on the printer's label). */
fun modelFromSerial(serial: String): String = when {
    serial.startsWith("00M") -> "X1C"
    serial.startsWith("00W") -> "X1"
    serial.startsWith("03W") -> "X1E"
    serial.startsWith("01S") -> "P1P"
    serial.startsWith("01P") -> "P1S"
    serial.startsWith("22E") -> "P2S"
    serial.startsWith("030") -> "A1 mini"
    serial.startsWith("039") -> "A1"
    serial.startsWith("094") -> "H2D"
    serial.startsWith("093") -> "H2S"
    else -> ""
}

/** The printer's stage numbers (stg_cur) with readable names, for "Heatbed preheating" etc. */
private val STAGES = mapOf(
    1 to "Auto bed leveling", 2 to "Heatbed preheating", 3 to "Vibration compensation",
    4 to "Changing filament", 5 to "Paused (M400)", 6 to "Paused: filament ran out",
    7 to "Heating nozzle", 8 to "Calibrating extrusion", 9 to "Scanning bed surface",
    10 to "Inspecting first layer", 11 to "Identifying build plate", 12 to "Calibrating Micro Lidar",
    13 to "Homing toolhead", 14 to "Cleaning nozzle tip", 15 to "Checking extruder temperature",
    16 to "Paused by user", 17 to "Paused: front cover fell", 18 to "Calibrating Micro Lidar",
    19 to "Calibrating extrusion flow", 20 to "Paused: nozzle temperature fault",
    21 to "Paused: bed temperature fault", 22 to "Unloading filament", 23 to "Paused: skipped step",
    24 to "Loading filament", 25 to "Calibrating motor noise", 26 to "Paused: AMS lost",
    27 to "Paused: low fan speed", 28 to "Paused: chamber temperature fault",
    29 to "Cooling chamber", 30 to "Paused by G-code", 31 to "Motor noise showoff",
    32 to "Paused: nozzle clumping", 33 to "Paused: cutter error", 34 to "Paused: first layer error",
    35 to "Paused: nozzle clog", 36 to "Checking absolute accuracy", 37 to "Absolute accuracy calibration",
    38 to "Checking absolute accuracy", 39 to "Calibrating nozzle offset", 40 to "Bed level (high temperature)",
    41 to "Checking quick release", 42 to "Checking door and cover", 43 to "Laser calibration",
    44 to "Checking platform", 45 to "Checking camera position", 46 to "Calibrating camera",
    47 to "Bed level (high temperature)", 48 to "Heating chamber",
)

private fun JSONObject.num(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
}

private fun JSONObject.int(key: String): Int? = num(key)?.roundToInt()

/** Some printers (H2D) pack temperatures as (target shl 16) or current. */
private fun unpackTemp(v: Int): Pair<Double, Double> =
    if (v > 0xFFFF) (v and 0xFFFF).toDouble() to (v ushr 16).toDouble() else v.toDouble() to 0.0

/** Fan speeds arrive as 0-15; the app shows percent in steps of 10. */
private fun fanPercent(raw: Int?): Int? = raw?.let { ((it.coerceIn(0, 15) / 15.0 * 10).roundToInt() * 10) }

private fun tray(o: JSONObject, fallbackId: Int): BbTray {
    val type = o.optString("tray_type", "")
    return BbTray(
        id = o.int("id") ?: fallbackId,
        colorHex = o.optString("tray_color", "").takeIf { it.isNotBlank() },
        type = type,
        remain = o.int("remain") ?: -1,
        exists = type.isNotBlank(),
    )
}

/** Turn the merged state into the same status model the Bambuddy screens use. */
fun toStatus(
    printerId: Int,
    name: String,
    serial: String,
    state: JSONObject,
    connected: Boolean,
): BbStatus {
    val p = state.optJSONObject("print") ?: JSONObject()
    val gcodeState = p.optString("gcode_state", "").uppercase(Locale.US)

    // Temperatures. Dual-nozzle printers report each extruder under device.extruder.info.
    var nozzle = p.num("nozzle_temper")
    var nozzleT = p.num("nozzle_target_temper")
    var nozzle2: Double? = null
    var nozzle2T: Double? = null
    // Extruder id 0 is the right (main) nozzle, id 1 the left. The app shows the left reading as
    // "nozzle" and the right as "nozzle2" (see BbTemps), so a dual printer's ids are swapped here.
    val extruders = p.optJSONObject("device")?.optJSONObject("extruder")?.optJSONArray("info")
    if (extruders != null && extruders.length() >= 1) {
        val dual = extruders.length() >= 2
        for (i in 0 until extruders.length()) {
            val e = extruders.optJSONObject(i) ?: continue
            val (cur, target) = unpackTemp(e.int("temp") ?: continue)
            when (e.int("id") ?: i) {
                0 -> if (dual) { nozzle2 = cur; nozzle2T = target } else { nozzle = cur; nozzleT = target }
                1 -> { nozzle = cur; nozzleT = target }
            }
        }
    }
    // extruder.state carries the nozzle in use in bits 4-7 (0 = right, 1 = left on the H2D).
    val activeNozzle = p.optJSONObject("device")?.optJSONObject("extruder")?.int("state")?.let { (it shr 4) and 0xF }
    var chamber = p.num("chamber_temper")
    var chamberT: Double? = null
    p.optJSONObject("device")?.optJSONObject("ctc")?.optJSONObject("info")?.int("temp")?.let {
        val (cur, target) = unpackTemp(it)
        chamber = cur
        chamberT = target
    }
    val temps = BbTemps(
        nozzle = nozzle, nozzleTarget = nozzleT,
        nozzle2 = nozzle2, nozzle2Target = nozzle2T,
        bed = p.num("bed_temper"), bedTarget = p.num("bed_target_temper"),
        chamber = chamber, chamberTarget = chamberT,
    )

    val light = p.optJSONArray("lights_report")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .any { it.optString("node").startsWith("chamber_light") && it.optString("mode") == "on" }
    } ?: false

    val amsUnits = p.optJSONObject("ams")?.optJSONArray("ams")
    val ams = if (amsUnits == null) emptyList() else (0 until amsUnits.length()).mapNotNull { i ->
        val u = amsUnits.optJSONObject(i) ?: return@mapNotNull null
        val trays = u.optJSONArray("tray") ?: JSONArray()
        val id = u.int("id") ?: i
        BbAms(
            id = id,
            isHt = id >= 128, // AMS HT units are numbered from 128
            humidity = u.int("humidity_raw"),
            temp = u.num("temp"),
            trays = (0 until trays.length()).mapNotNull { t -> trays.optJSONObject(t)?.let { tray(it, t) } },
        )
    }

    val external = buildList {
        p.optJSONObject("vt_tray")?.let { add(tray(it, 254)) }
        p.optJSONArray("vir_slot")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(tray(it, 254 + i)) } }
    }.filter { it.exists }

    val hmsArr = p.optJSONArray("hms")
    val hms = if (hmsArr == null) emptyList() else (0 until hmsArr.length()).mapNotNull { i ->
        val h = hmsArr.optJSONObject(i) ?: return@mapNotNull null
        val attr = h.optLong("attr")
        val code = h.optLong("code")
        val full = String.format(
            Locale.US, "%04X_%04X_%04X_%04X",
            (attr ushr 16) and 0xFFFF, attr and 0xFFFF, (code ushr 16) and 0xFFFF, code and 0xFFFF,
        )
        BbHms(code = "HMS_$full", description = null, severity = ((code ushr 16) and 0xFF).toInt())
    }

    val stageNum = p.int("stg_cur")
    val active = gcodeState == "RUNNING" || gcodeState == "PREPARE"
    val stage = if (active && stageNum != null && stageNum in STAGES) STAGES[stageNum] else null
    val model = modelFromSerial(serial)

    return BbStatus(
        id = printerId,
        name = name,
        connected = connected && p.length() > 0,
        state = gcodeState,
        job = p.optString("subtask_name", "").ifBlank { p.optString("gcode_file", "") },
        progress = p.num("mc_percent"),
        remainingMin = p.int("mc_remaining_time"),
        layer = p.int("layer_num"),
        totalLayers = p.int("total_layer_num"),
        temps = temps,
        light = light,
        speedLevel = p.int("spd_lvl") ?: 2,
        partFan = fanPercent(p.int("cooling_fan_speed")),
        auxFan = fanPercent(p.int("big_fan1_speed")),
        chamberFan = fanPercent(p.int("big_fan2_speed")),
        ams = ams,
        external = external,
        hms = hms,
        dualNozzle = nozzle2 != null,
        supportsChamberHeater = model == "H2D" || model == "H2S" || model == "X1E" || chamberT != null,
        awaitingPlateClear = false,
        stage = stage,
        model = model,
        activeNozzle = activeNozzle?.takeIf { nozzle2 != null && it in 0..1 },
        raw = state.toString(2),
    )
}

/** JSON commands understood by Bambu printers on the request topic. */
object DirectCommands {
    private var seq = 0
    private fun next() = synchronized(this) { (++seq).toString() }

    fun pushAll(): String = JSONObject().put(
        "pushing", JSONObject().put("sequence_id", next()).put("command", "pushall").put("version", 1).put("push_target", 1),
    ).toString()

    private fun print(command: String, param: String = ""): String =
        JSONObject().put("print", JSONObject().put("sequence_id", next()).put("command", command).put("param", param)).toString()

    fun pause() = print("pause")
    fun resume() = print("resume")
    fun stop() = print("stop")
    fun speed(level: Int) = print("print_speed", level.coerceIn(1, 4).toString())
    fun gcode(line: String) = print("gcode_line", line.trimEnd() + "\n")

    fun light(node: String, on: Boolean): String = JSONObject().put(
        "system",
        JSONObject().put("sequence_id", next()).put("command", "ledctrl").put("led_node", node)
            .put("led_mode", if (on) "on" else "off").put("led_on_time", 500).put("led_off_time", 500)
            .put("loop_times", 0).put("interval_time", 0),
    ).toString()

    fun skipObjects(ids: List<Int>): String = JSONObject().put(
        "print", JSONObject().put("sequence_id", next()).put("command", "skip_objects").put("obj_list", JSONArray(ids)),
    ).toString()
}
