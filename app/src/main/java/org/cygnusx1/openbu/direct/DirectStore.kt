package org.cygnusx1.openbu.direct

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A printer BamMate talks to directly over the local network. */
data class DirectPrinter(
    val id: Int,
    val name: String,
    val ip: String,
    val serial: String,
    val accessCode: String,
) {
    val model: String get() = modelFromSerial(serial)
    val displayName: String get() = name.ifBlank { model.ifBlank { serial } }
}

/**
 * The saved direct printers, kept as one JSON list in the app's encrypted preferences.
 *
 * Printers saved by earlier versions of the app (one key per field) are copied over once,
 * the first time the list is read, so nobody has to enter them again.
 */
object DirectStore {
    private const val KEY = "direct_printers"
    private const val KEY_IMPORTED = "direct_printers_imported"

    fun load(prefs: SharedPreferences): List<DirectPrinter> {
        if (!prefs.getBoolean(KEY_IMPORTED, false)) importOld(prefs)
        return parse(prefs.getString(KEY, null))
    }

    fun save(prefs: SharedPreferences, printers: List<DirectPrinter>) {
        prefs.edit().putString(KEY, encode(printers)).apply()
    }

    /** Adds a printer or replaces the one with the same id. Returns the saved entry. */
    fun upsert(prefs: SharedPreferences, printer: DirectPrinter): DirectPrinter {
        val list = load(prefs).toMutableList()
        val saved = if (printer.id <= 0) printer.copy(id = (list.maxOfOrNull { it.id } ?: 0) + 1) else printer
        val i = list.indexOfFirst { it.id == saved.id }
        if (i >= 0) list[i] = saved else list += saved
        save(prefs, list)
        return saved
    }

    fun delete(prefs: SharedPreferences, id: Int) {
        save(prefs, load(prefs).filterNot { it.id == id })
    }

    fun parse(json: String?): List<DirectPrinter> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = try {
            JSONArray(json)
        } catch (_: Exception) {
            return emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ip = o.optString("ip").trim()
            val serial = o.optString("serial").trim()
            if (ip.isBlank() || serial.isBlank()) return@mapNotNull null
            DirectPrinter(
                id = o.optInt("id", i + 1),
                name = o.optString("name").trim(),
                ip = ip,
                serial = serial,
                accessCode = o.optString("code").trim(),
            )
        }
    }

    fun encode(printers: List<DirectPrinter>): String = JSONArray().apply {
        printers.forEach {
            put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("ip", it.ip)
                    .put("serial", it.serial)
                    .put("code", it.accessCode),
            )
        }
    }.toString()

    /** One-time copy of printers saved by earlier versions of the app. */
    private fun importOld(prefs: SharedPreferences) {
        val existing = parse(prefs.getString(KEY, null))
        val serials = (prefs.getString("saved_printer_serials", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        val imported = serials.mapNotNull { serial ->
            if (existing.any { it.serial == serial }) return@mapNotNull null
            val ip = prefs.getString("saved_ip_$serial", "") ?: ""
            if (ip.isBlank()) return@mapNotNull null
            val name = (prefs.getString("custom_name_$serial", "") ?: "")
                .ifBlank { prefs.getString("saved_name_$serial", "") ?: "" }
            Triple(serial, ip, name)
        }
        var nextId = (existing.maxOfOrNull { it.id } ?: 0) + 1
        val merged = existing + imported.map { (serial, ip, name) ->
            DirectPrinter(
                id = nextId++,
                name = name,
                ip = ip,
                serial = serial,
                accessCode = prefs.getString("access_code_$serial", "") ?: "",
            )
        }
        prefs.edit()
            .putString(KEY, encode(merged))
            .putBoolean(KEY_IMPORTED, true)
            .apply()
    }
}
