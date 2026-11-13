package org.cygnusx1.openbu.bambuddy

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** Display name for an AMS unit: "AMS A", "AMS B", or "AMS HT". */
fun BbAms.displayName(): String = if (isHt) "AMS HT" else "AMS ${'A' + (id % 26)}"

/**
 * Whether this unit has a heater the app should offer to run. The printer must support drying,
 * and the original AMS (module type "ams") and AMS Lite have no heater.
 */
fun BbAms.canDry(s: BbStatus): Boolean =
    s.supportsDrying && moduleType.lowercase() !in setOf("ams", "ams_lite", "ams-lite")

/** The AMS HT heats higher than the AMS 2 Pro. */
private fun BbAms.maxDryTemp(): Int = if (isHt || moduleType.equals("n3s", ignoreCase = true)) 85 else 65

/** "Drying PETG  -  65°C  -  1h 20m left" */
fun BbAms.dryingText(): String = listOfNotNull(
    "Drying" + (dryFilament?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""),
    dryTargetTemp?.let { "$it°C" },
    dryMinutesLeft.takeIf { it > 0 }?.let { m -> if (m >= 60) "${m / 60}h ${m % 60}m left" else "${m}m left" },
).joinToString("  -  ")

/** Fallback drying settings by filament type, used when the spool doesn't carry its own. */
private fun suggested(type: String): Pair<Int, Int> = when (type.uppercase().substringBefore('-').trim()) {
    "PLA" -> 50 to 8
    "PETG", "PET" -> 65 to 8
    "TPU" -> 60 to 8
    "ABS", "ASA" -> 75 to 8
    "PA", "PAHT", "PA6", "PA12" -> 80 to 12
    "PC" -> 80 to 8
    "PVA", "BVOH" -> 55 to 8
    else -> 55 to 6
}

private val COMMON_TYPES = listOf("PLA", "PETG", "TPU", "ABS", "ASA", "PA", "PC")

/** Start-drying dialog, like the Bambuddy web one: filament, temperature, hours, rotate, when. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DryingDialog(s: BbStatus, u: BbAms, vm: BambuddyViewModel, onDismiss: () -> Unit) {
    val maxTemp = u.maxDryTemp()
    val loaded = u.trays.filter { it.exists && it.type.isNotBlank() }
    val types = (loaded.map { it.type } + COMMON_TYPES).distinct()

    fun defaultsFor(type: String): Pair<Int, Int> {
        val spool = loaded.firstOrNull { it.type == type }
        val (t, h) = suggested(type)
        return (spool?.dryingTemp ?: t).coerceIn(45, maxTemp) to (spool?.dryingHours ?: h).coerceIn(1, 24)
    }

    var filament by remember { mutableStateOf(types.first()) }
    var temp by remember { mutableFloatStateOf(defaultsFor(types.first()).first.toFloat()) }
    var hours by remember { mutableFloatStateOf(defaultsFor(types.first()).second.toFloat()) }
    var rotate by remember { mutableStateOf(false) }
    var delayHours by remember { mutableIntStateOf(0) } // 0 = start now

    val busyPrinting = s.state in setOf("RUNNING", "PAUSE", "PREPARE") && !s.supportsDryingWhilePrinting

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dry ${u.displayName()}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (busyPrinting) {
                    Text(
                        "${s.name} can't dry while it's printing. You can schedule it for later instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text("Filament", style = MaterialTheme.typography.labelLarge)
                // Wraps onto more lines instead of scrolling, so no option is hidden off-screen.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    types.forEach { t ->
                        FilterChip(
                            selected = t == filament,
                            onClick = {
                                filament = t
                                val (dt, dh) = defaultsFor(t)
                                temp = dt.toFloat()
                                hours = dh.toFloat()
                            },
                            label = { Text(t) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Temperature", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("${temp.roundToInt()}°C", fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = temp,
                    onValueChange = { temp = it.roundToInt().toFloat() },
                    valueRange = 45f..maxTemp.toFloat(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Duration", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("${hours.roundToInt()} h", fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = hours,
                    onValueChange = { hours = it.roundToInt().toFloat() },
                    valueRange = 1f..24f,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rotate spool while drying", modifier = Modifier.weight(1f))
                    Switch(checked = rotate, onCheckedChange = { rotate = it })
                }

                Text("Start", style = MaterialTheme.typography.labelLarge)
                // Wraps onto more lines instead of scrolling, so no option is hidden off-screen.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(0, 1, 2, 4, 8).forEach { d ->
                        FilterChip(
                            selected = delayHours == d,
                            onClick = { delayHours = d },
                            label = { Text(if (d == 0) "Now" else "In $d h") },
                            enabled = !(busyPrinting && d == 0),
                        )
                    }
                }
                if (delayHours > 0) {
                    val at = OffsetDateTime.now().plusHours(delayHours.toLong())
                    Text(
                        "Starts at " + at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) +
                            ", finishes around " +
                            at.plusHours(hours.roundToInt().toLong()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !(busyPrinting && delayHours == 0),
                onClick = {
                    val t = temp.roundToInt()
                    val h = hours.roundToInt()
                    val f = filament
                    val r = rotate
                    if (delayHours == 0) {
                        vm.command { startDrying(s.id, u.id, t, h, f, r) }
                    } else {
                        val start = OffsetDateTime.now().plusHours(delayHours.toLong()).withNano(0).toString()
                        vm.command { scheduleDrying(s.id, u.id, t, h, f, r, start) }
                    }
                    onDismiss()
                },
            ) { Text(if (delayHours == 0) "Start drying" else "Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

data class BbScheduledDry(
    val id: Int,
    val printerId: Int,
    val amsId: Int,
    val temp: Int,
    val hours: Int,
    val filament: String,
    val startAfter: String?,
    val status: String,
    val waitingReason: String?,
) {
    /** Scheduled entries that haven't started or ended yet. */
    val pending: Boolean
        get() = status.lowercase() !in setOf(
            "completed", "complete", "done", "finished", "cancelled", "canceled",
            "failed", "error", "started", "running", "drying",
        )

    /** "12:55 AM" or "Sat 9:00 AM" in the phone's time zone. */
    fun startText(): String? {
        val raw = startAfter ?: return null
        val local = try {
            java.time.OffsetDateTime.parse(raw).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime()
        } catch (_: Exception) {
            try {
                // No offset in the reply: the server stores UTC.
                java.time.LocalDateTime.parse(raw.take(19)).atZone(java.time.ZoneOffset.UTC)
                    .withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime()
            } catch (_: Exception) {
                return raw
            }
        }
        val time = local.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        return if (local.toLocalDate() == java.time.LocalDate.now()) time
        else local.format(DateTimeFormatter.ofPattern("EEE")) + " " + time
    }
}

fun parseScheduledDryings(body: String): List<BbScheduledDry> {
    val t = body.trim()
    if (!t.startsWith("[")) return emptyList()
    val arr = org.json.JSONArray(t)
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        BbScheduledDry(
            id = o.optInt("id"),
            printerId = o.optInt("printer_id"),
            amsId = o.optInt("ams_id"),
            temp = o.optInt("temp"),
            hours = o.optInt("duration_hours"),
            filament = o.optString("filament", ""),
            startAfter = if (o.isNull("start_after")) null else o.optString("start_after"),
            status = o.optString("status", ""),
            waitingReason = if (o.isNull("waiting_reason")) null else o.optString("waiting_reason").takeIf { it.isNotBlank() },
        )
    }
}

/** One line per scheduled drying for this AMS, with a Cancel button. */
@Composable
fun ScheduledDryRows(list: List<BbScheduledDry>, vm: BambuddyViewModel) {
    list.forEach { d ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    listOfNotNull(
                        "Scheduled" + (d.filament.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""),
                        "${d.temp}°C",
                        "${d.hours} h",
                        d.startText()?.let { "starts $it" },
                    ).joinToString("  -  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                d.waitingReason?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { vm.command { cancelScheduledDrying(d.id) } }) { Text("Cancel") }
        }
    }
}

/** Shown inside an AMS panel while it is drying. */
@Composable
fun DryingStrip(s: BbStatus, u: BbAms, vm: BambuddyViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            u.dryingText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { vm.command { stopDrying(s.id, u.id) } }) { Text("Stop") }
    }
}
