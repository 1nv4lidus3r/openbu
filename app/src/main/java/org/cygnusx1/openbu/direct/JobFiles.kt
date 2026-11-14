package org.cygnusx1.openbu.direct

import org.json.JSONObject

/** One printable object on a plate, from the slicer's plate_N.json: its skip id, name and outline (mm). */
data class PlateObject(val id: Int, val name: String, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/**
 * Finds the running print's .3mf on the printer and reads things from its start.
 *
 * Prints sent from a slicer report their project name ("subtask_name"); the .3mf of that name sits
 * in the storage root, /cache or /model. Prints started from the printer's screen don't say which
 * file they are, so nothing is found for them.
 */
object JobFiles {
    private val FOLDERS = listOf("/", "/cache", "/model")

    /** Path of the .3mf for [job], preferring an exact name match, then the newest. */
    fun find(ftps: PrinterFtps, job: String): String? {
        if (job.isBlank() || job.startsWith("/")) return null
        val key = job.lowercase().removeSuffix(".gcode.3mf").removeSuffix(".3mf").removeSuffix(".gcode")
        val candidates = FOLDERS.flatMap { dir ->
            runCatching { ftps.list(dir) }.getOrDefault(emptyList())
                .filter { !it.isDir && it.name.lowercase().endsWith(".3mf") && it.name.lowercase().startsWith(key) }
                .map { (if (dir == "/") "/" else "$dir/") + it.name to it }
        }
        return candidates.sortedWith(
            compareByDescending<Pair<String, FtpEntry>> {
                it.second.name.lowercase().removeSuffix(".gcode.3mf").removeSuffix(".3mf") == key
            }.thenByDescending { it.second.time ?: 0L },
        ).firstOrNull()?.first
    }

    /** "/data/Metadata/plate_2.gcode" -> "2"; null when the plate isn't in the name. */
    fun plateNumber(jobFile: String?): String? =
        jobFile?.let { Regex("plate_(\\d+)").findAll(it).lastOrNull()?.groupValues?.get(1) }

    /** The objects of the running plate, or null when the print's file can't be found or read. */
    fun objects(ftps: PrinterFtps, job: String, jobFile: String?): List<PlateObject>? {
        val path = find(ftps, job) ?: return null
        val plate = plateNumber(jobFile) ?: plateNumber(path) ?: "1"
        val (input, _) = ftps.download(path)
        val json = input.use { PlatePicture.findEntry(it, "Metadata/plate_$plate.json") } ?: return null
        return parsePlateJson(String(json, Charsets.UTF_8))
    }

    fun parsePlateJson(text: String): List<PlateObject> {
        val arr = JSONObject(text).optJSONArray("bbox_objects") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val b = o.optJSONArray("bbox") ?: return@mapNotNull null
            if (b.length() < 4) return@mapNotNull null
            PlateObject(
                id = o.optInt("id"),
                name = o.optString("name").substringBeforeLast('.').ifBlank { "Object" },
                minX = b.optDouble(0), minY = b.optDouble(1), maxX = b.optDouble(2), maxY = b.optDouble(3),
            )
        }
    }
}
