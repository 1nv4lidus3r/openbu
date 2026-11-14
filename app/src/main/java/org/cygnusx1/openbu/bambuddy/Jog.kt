package org.cygnusx1.openbu.bambuddy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The printer firmware refuses to extrude below this, so the buttons wait for it. */
private const val MIN_EXTRUDE_TEMP = 170

/**
 * Jog pad: move X/Y, raise or lower the bed (Z), extrude or retract, and home. Only offered
 * while the printer isn't printing. The printer does not check its travel limits for these moves,
 * so the pad says so, offers Home first, and asks before every 50 mm step. The pad is only
 * reachable after the user turned on Manual moves in Settings and accepted the risk notice.
 */
@Composable
fun JogDialog(s: BbStatus, vm: BambuddyViewModel, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(10) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val haptic = LocalHapticFeedback.current

    // The nozzle in use: on the H2D 0 = right (nozzle2 reading), 1 = left (nozzle reading).
    val nozzleTemp = when {
        s.dualNozzle && s.activeNozzle == 0 -> s.temps.nozzle2
        else -> s.temps.nozzle
    } ?: 0.0
    val canExtrude = nozzleTemp >= MIN_EXTRUDE_TEMP

    fun move(block: PrinterBackend.() -> Unit) {
        val run = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            vm.control(block)
        }
        if (step >= 50) pending = run else run()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${s.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = LowAmber.copy(alpha = 0.12f),
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = LowAmber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "The printer doesn't check its travel limits for remote moves. Home first, " +
                                "then move in small steps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LowAmber,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // X / Y pad with Home in the middle.
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PadButton(Icons.Filled.KeyboardArrowUp, "Y+") { move { jogXY(s.id, 0, step) } }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PadButton(Icons.Filled.KeyboardArrowLeft, "X-") { move { jogXY(s.id, -step, 0) } }
                            PadButton(Icons.Filled.Home, "Home all", accent = true) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.control { homeAll(s.id) }
                            }
                            PadButton(Icons.Filled.KeyboardArrowRight, "X+") { move { jogXY(s.id, step, 0) } }
                        }
                        PadButton(Icons.Filled.KeyboardArrowDown, "Y-") { move { jogXY(s.id, 0, -step) } }
                    }
                    // Z moves the bed on Bambu printers. The arrows show which way the BED goes:
                    // up = bed toward the nozzle (Z minus), down = bed away from it (Z plus).
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PadButton(Icons.Filled.KeyboardArrowUp, "Bed up") { move { jogZ(s.id, -step) } }
                        Text("Bed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PadButton(Icons.Filled.KeyboardArrowDown, "Bed down") { move { jogZ(s.id, step) } }
                    }
                    // Extruder: down pushes filament out, up pulls it back. Max 10 mm per press.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.alpha(if (canExtrude) 1f else 0.4f),
                    ) {
                        val e = minOf(step, 10)
                        PadButton(Icons.Filled.ArrowUpward, "Retract", enabled = canExtrude) { move { jogExtruder(s.id, -e) } }
                        Text("E", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PadButton(Icons.Filled.ArrowDownward, "Extrude", enabled = canExtrude) { move { jogExtruder(s.id, e) } }
                    }
                }

                if (!canExtrude) {
                    Text(
                        "Extrude and retract need the nozzle at $MIN_EXTRUDE_TEMP° or more (now ${nozzleTemp.roundToInt()}°).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column {
                    Text("Step", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 10, 50).forEach { mm ->
                            FilterChip(
                                selected = step == mm,
                                onClick = { step = mm },
                                label = { Text("$mm mm") },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    pending?.let { run ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Move 50 mm?") },
            text = { Text("That's a big step. Make sure the head and bed have room to move.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    run()
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PadButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
