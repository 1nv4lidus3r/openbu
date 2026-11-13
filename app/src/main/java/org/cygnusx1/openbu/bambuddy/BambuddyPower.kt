package org.cygnusx1.openbu.bambuddy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Models (SmartPlugResponse / SmartPlugStatus / SmartPlugEnergy in the Bambuddy spec)
// ---------------------------------------------------------------------------------------------

data class BbPlug(
    val id: Int,
    val name: String,
    val autoOff: Boolean,
    val offDelayMode: String,
    val offDelayMinutes: Int,
    val offTempThreshold: Int,
    val lastState: String?,
)

data class BbPlugStatus(
    /** "ON", "OFF" or null when unknown. */
    val state: String?,
    val reachable: Boolean,
    val watts: Double?,
    val todayKwh: Double?,
)

/** Everything the card needs about one printer's power. plug == null means none is assigned. */
data class BbPower(val plug: BbPlug?, val status: BbPlugStatus?) {
    val isOn: Boolean? get() = status?.state?.uppercase()?.let { it == "ON" } ?: plug?.lastState?.uppercase()?.let { it == "ON" }
}

private fun JSONObject.dbl(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

/** Bambuddy answers "null" (or 404) when no plug is assigned to the printer. */
fun parsePlug(body: String): BbPlug? {
    val t = body.trim()
    if (!t.startsWith("{")) return null
    val o = JSONObject(t)
    if (!o.has("id")) return null
    return BbPlug(
        id = o.optInt("id"),
        name = o.optString("name", "Smart plug"),
        autoOff = o.optBoolean("auto_off", false),
        offDelayMode = o.optString("off_delay_mode", "time"),
        offDelayMinutes = o.optInt("off_delay_minutes", 5),
        offTempThreshold = o.optInt("off_temp_threshold", 70),
        lastState = if (o.isNull("last_state")) null else o.optString("last_state"),
    )
}

fun parsePlugStatus(body: String): BbPlugStatus {
    val o = JSONObject(body)
    val e = o.optJSONObject("energy")
    return BbPlugStatus(
        state = if (o.isNull("state")) null else o.optString("state").takeIf { it.isNotBlank() },
        reachable = o.optBoolean("reachable", true),
        watts = e?.dbl("power"),
        todayKwh = e?.dbl("today"),
    )
}

/** "301 W" for the card header, or null when there is nothing to show. */
fun BbPower.headerText(): String? {
    val w = status?.watts ?: return null
    return if (isOn == false) null else "${w.roundToInt()} W"
}

// ---------------------------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------------------------

/** Power row for the printer card: state, watts, on/off button and the auto power-off switch. */
@Composable
fun PowerSection(
    printerName: String,
    printing: Boolean,
    power: BbPower,
    onSetPower: (Boolean) -> Unit,
    onSetAutoOff: (Boolean) -> Unit,
) {
    val plug = power.plug ?: return
    val st = power.status
    val on = power.isOn
    var confirmOff by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Bolt,
                contentDescription = null,
                tint = when (on) {
                    true -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(plug.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = buildList {
                        add(
                            when {
                                st != null && !st.reachable -> "Plug unreachable"
                                on == true -> "On"
                                on == false -> "Off"
                                else -> "Unknown"
                            },
                        )
                        st?.watts?.let { add("${it.roundToInt()} W") }
                        st?.todayKwh?.let { add(String.format(Locale.US, "%.2f kWh today", it)) }
                    }.joinToString("  -  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (st != null && !st.reachable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (on == true) {
                OutlinedButton(onClick = { confirmOff = true }) { Text("Turn off") }
            } else {
                FilledTonalButton(onClick = { onSetPower(true) }) { Text("Turn on") }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto power-off after print", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (plug.offDelayMode == "temperature") {
                        "Turns off once the nozzle cools below ${plug.offTempThreshold}°C"
                    } else {
                        "Turns off ${plug.offDelayMinutes} min after the print ends"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = plug.autoOff, onCheckedChange = onSetAutoOff)
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn off $printerName?") },
            text = {
                Text(
                    if (printing) {
                        "$printerName is printing right now. Cutting the power will ruin the print and can't be undone."
                    } else {
                        "This switches off the smart plug that powers $printerName."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    onSetPower(false)
                }) {
                    Text(if (printing) "Turn off anyway" else "Turn off", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
        )
    }
}
