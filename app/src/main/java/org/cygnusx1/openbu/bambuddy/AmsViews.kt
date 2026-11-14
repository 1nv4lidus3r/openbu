package org.cygnusx1.openbu.bambuddy

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Drying glow color for an AMS unit (warm orange, like the heater). */
val DryingOrange = Color(0xFFFF9F1C)

/** Outline of the AMS seen from the front (lid on top, wider base, plinth), in 0..1 units. */
private fun amsOutline(w: Float, h: Float, tall: Boolean): Path = Path().apply {
    // Proportions traced from the product photos of the AMS (wide) and AMS HT (tall).
    val lidTopL = if (tall) 0.108f else 0.033f
    val lidBotL = if (tall) 0.07f else 0.02f
    val lidBot = if (tall) 0.489f else 0.44f
    val baseTop = if (tall) 0.497f else 0.455f
    val baseBot = if (tall) 0.956f else 0.949f
    val plinth = if (tall) 0.056f else 0.057f
    val r = 0.03f * w
    moveTo(lidTopL * w + r, 0f)
    lineTo((1 - lidTopL) * w - r, 0f)
    quadraticTo((1 - lidTopL) * w, 0f, (1 - lidTopL) * w + 0.002f * w, r)
    lineTo((1 - lidBotL) * w, lidBot * h)
    lineTo(w, baseTop * h)
    lineTo(w, baseBot * h)
    lineTo((1 - plinth) * w, baseBot * h)
    lineTo((1 - plinth) * w, h)
    lineTo(plinth * w, h)
    lineTo(plinth * w, baseBot * h)
    lineTo(0f, baseBot * h)
    lineTo(0f, baseTop * h)
    lineTo(lidBotL * w, lidBot * h)
    lineTo(lidTopL * w - 0.002f * w, r)
    quadraticTo(lidTopL * w, 0f, lidTopL * w + r, 0f)
    close()
}

/** Draws the unit: the base a shade lighter than the card, the smoked lid darker, a faint seam between. */
@Composable
private fun AmsShape(tall: Boolean, modifier: Modifier) {
    // A shade lighter than the card for the body, a smoked dark lid, so the shape reads on OLED black.
    val card = MaterialTheme.colorScheme.surfaceContainerHighest
    val body = androidx.compose.ui.graphics.lerp(card, Color.White, 0.07f)
    val lid = androidx.compose.ui.graphics.lerp(card, Color.Black, 0.45f)
    val seam = Color.White.copy(alpha = 0.06f)
    Canvas(modifier = modifier) {
        val outline = amsOutline(size.width, size.height, tall)
        drawPath(outline, body)
        clipPath(outline) {
            val lidBottom = size.height * (if (tall) 0.493f else 0.448f)
            drawRect(lid, size = Size(size.width, lidBottom))
            drawRect(seam, topLeft = Offset(0f, lidBottom), size = Size(size.width, 1.dp.toPx()))
        }
        drawPath(outline, Color.White.copy(alpha = 0.10f), style = Stroke(1.dp.toPx()))
    }
}

/**
 * The AMS drawn from the front, like the real unit: four spools sit in the smoked lid, seen from
 * the side. The colored filament ring shrinks toward the hub as the spool is used up (by area, so
 * half the grams looks like half the filament). An empty slot is a dashed spool outline; the slot
 * feeding the printer gets a white ring. Under each spool on the base: slot and material, the
 * spool's name and brand, and the grams left (from HaspelSync / Spoolman when linked).
 */
@Composable
fun AmsBody(u: BbAms, activeSlot: Int?, linked: Map<String, HsSpool>, modifier: Modifier = Modifier) {
    val slots = if (u.trays.isEmpty()) List(4) { BbTray(it, null, "", -1, false) } else u.trays
    Box(modifier = modifier.fillMaxWidth().aspectRatio(2.0f)) {
        AmsShape(tall = false, modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Spacer(Modifier.weight(0.04f))
            Row(
                modifier = Modifier.fillMaxWidth().weight(0.41f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                slots.forEach { t ->
                    SpoolCircle(
                        t, linked[HaspelSyncClient.slotLabel(u.id, t.id)], active = activeSlot == t.id,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            Spacer(Modifier.weight(0.08f))
            Row(
                modifier = Modifier.fillMaxWidth().weight(0.44f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                slots.forEach { t ->
                    SlotText(
                        slotLabel(u, t), t, linked[HaspelSyncClient.slotLabel(u.id, t.id)],
                        active = activeSlot == t.id, modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.weight(0.03f))
        }
    }
}

/**
 * The AMS HT: one tall unit with its spool, and the spool details beside it (brand, name, grams
 * and a bar when linked in HaspelSync).
 */
@Composable
fun AmsHtBody(u: BbAms, active: Boolean, linked: Map<String, HsSpool>, modifier: Modifier = Modifier) {
    val t = u.trays.firstOrNull() ?: BbTray(0, null, "", -1, false)
    val spool = linked[HaspelSyncClient.slotLabel(u.id, t.id)]
    val empty = !t.exists || t.type.isBlank()
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(80.dp).aspectRatio(0.62f)) {
            AmsShape(tall = true, modifier = Modifier.fillMaxSize())
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Spacer(Modifier.weight(0.06f))
                SpoolCircle(t, spool, active = active, modifier = Modifier.fillMaxWidth().weight(0.40f))
                Spacer(Modifier.weight(0.09f))
                SlotText(slotLabel(u, t), t, null, active = active, compact = true, modifier = Modifier.fillMaxWidth().weight(0.38f))
                Spacer(Modifier.weight(0.07f))
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                empty -> {
                    Text("Empty", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("No spool loaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                spool != null -> {
                    if (spool.vendor.isNotBlank()) {
                        Text(spool.vendor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(spool.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (spool.initialG > 0) "${spool.gramsLabel} of ${spool.initialG.roundToInt()} g left" else "${spool.gramsLabel} left",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (spool.low) LowAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    spool.fraction?.let {
                        Spacer(Modifier.height(6.dp))
                        LeftBar(it, parseTrayColor(t.colorHex ?: spool.colorHex))
                    }
                }
                else -> {
                    Text(t.type, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (t.remain in 1..100) "${t.remain}% left" else "Amount left unknown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (t.remain in 1..100) {
                        Spacer(Modifier.height(6.dp))
                        LeftBar(t.remain / 100f, parseTrayColor(t.colorHex))
                    }
                }
            }
            if (active) {
                Spacer(Modifier.height(4.dp))
                Text("Feeding the printer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// The printer reports remain 0 (or -1) when it doesn't know the amount (third-party spools,
// the external holder), so only 1..100 counts as a real percent.

/** Amber for spools running low. */
val LowAmber = Color(0xFFFFB020)

/**
 * External spool holders, drawn as the same side-on spools as the AMS (one on most printers,
 * two on the H2D), with the linked spool's name and grams under each.
 */
@Composable
fun ExternalSpools(trays: List<BbTray>, activeIndex: Int?, linked: Map<String, HsSpool>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        trays.forEachIndexed { i, t ->
            // Two holders (H2D) come left first, like Bambuddy's L / R. HaspelSync calls the right
            // (main) holder "External" and the left one "External-2" (checked on a real H2D).
            val dual = trays.size == 2
            val label = when {
                !dual -> "Ext"
                i == 0 -> "Left"
                else -> "Right"
            }
            val hsLabel = if (dual && i == 0) "External-2" else "External"
            val spool = linked[hsLabel]
            Column(modifier = Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SpoolCircle(t, spool, active = activeIndex == i, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(6.dp))
                SlotText(label, t, spool, active = activeIndex == i, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Thin bar showing how much of the spool is left, in the filament color. */
@Composable
fun LeftBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), if (animate) tween(900) else snap(), label = "leftBar")
    val track = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainerHighest, Color.Black, 0.4f)
    val rim = if (color.luminance() < 0.15f) Color.White.copy(alpha = 0.35f) else Color.Transparent
    Canvas(modifier = modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (f > 0f) {
            drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
            drawRoundRect(rim, size = Size(size.width * f, size.height), cornerRadius = r, style = Stroke(1.dp.toPx()))
        }
    }
}

/** "A1".."D4" on a normal AMS (unit 0 = A), "HT" on an AMS HT. */
private fun slotLabel(u: BbAms, t: BbTray): String =
    if (u.isHt) "HT" else "${'A' + (u.id % 26)}${t.id + 1}"

/**
 * Text under a spool: "A2 · PLA", then (when linked) the spool's short name, its brand and the
 * grams left (amber when low). Without a linked spool: the printer's percent, or "? left".
 * [compact] (AMS HT) keeps to the slot, material and amount, since the details sit beside the unit.
 */
@Composable
private fun SlotText(
    label: String,
    t: BbTray,
    spool: HsSpool?,
    active: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val empty = !t.exists || t.type.isBlank()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (empty || compact) label else "$label · ${t.type}",
            style = MaterialTheme.typography.labelSmall,
            color = dim,
            maxLines = 1,
        )
        when {
            empty -> Text("Empty", style = MaterialTheme.typography.labelMedium, color = dim)
            compact -> {
                Text(t.type, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            spool != null -> {
                Text(
                    spool.shortName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (spool.vendor.isNotBlank()) {
                    Text(spool.vendor, style = MaterialTheme.typography.labelSmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    spool.gramsLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (spool.low) LowAmber else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            else -> Text(
                if (t.remain in 1..100) "${t.remain}%" else "? left",
                style = MaterialTheme.typography.labelMedium,
                color = dim,
                maxLines = 1,
            )
        }
    }
}

/**
 * One spool seen from the side: flange rim, hub, and the filament ring in the spool's color.
 * The ring's outer edge is set so its area matches the amount left (grams from Spoolman, else the
 * printer's percent, else shown full). Empty slot: dashed rim and hub.
 */
@Composable
private fun SpoolCircle(t: BbTray, spool: HsSpool?, active: Boolean, modifier: Modifier = Modifier) {
    val empty = !t.exists || t.type.isBlank()
    val target = when {
        empty -> 0f
        spool?.fraction != null -> spool.fraction!!
        t.remain in 1..100 -> t.remain / 100f
        else -> 1f
    }
    val animate = animationsEnabled()
    val fill by animateFloatAsState(target, if (animate) tween(900) else snap(), label = "spoolFill")
    // The printer's slot color is what the AMS shows; Spoolman's is the fallback.
    val color = parseTrayColor(t.colorHex ?: spool?.colorHex)
    val dark = color.luminance() < 0.15f
    val outline = MaterialTheme.colorScheme.outline
    // A mid-dark flange, so black filament (darker) and light filament (lighter) both stand out.
    val flange = Color(0xFF34333A)
    val rimColor = Color(0xFF6B6870)
    Canvas(modifier = modifier) {
        val ringGap = 3.dp.toPx()
        val r = minOf(size.width, size.height) / 2 - ringGap - 1.dp.toPx()
        val hub = r * 0.32f
        if (empty) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            drawCircle(outline.copy(alpha = 0.7f), radius = r, style = Stroke(1.2.dp.toPx(), pathEffect = dash))
            drawCircle(outline.copy(alpha = 0.4f), radius = hub, style = Stroke(1.dp.toPx(), pathEffect = dash))
        } else {
            drawCircle(flange, radius = r)
            drawCircle(rimColor, radius = r, style = Stroke(1.5.dp.toPx()))
            val filled = kotlin.math.sqrt(hub * hub + fill * (r * r - hub * hub)) * 0.97f
            if (fill > 0f) {
                drawCircle(color, radius = filled)
                if (dark) drawCircle(Color.White.copy(alpha = 0.35f), radius = filled, style = Stroke(1.dp.toPx()))
            }
            drawCircle(Color(0xFF2B2A30), radius = hub)
            drawCircle(rimColor, radius = hub, style = Stroke(1.dp.toPx()))
            drawCircle(flange, radius = hub * 0.4f)
        }
        if (active) drawCircle(Color.White, radius = r + ringGap, style = Stroke(2.dp.toPx()))
    }
}

/**
 * Humidity as a water drop filled to the humidity level: green when dry (under 30%), amber
 * (30-50%), red when damp (over 50%). The percent sits next to it.
 */
@Composable
fun HumidityDrop(percent: Int, modifier: Modifier = Modifier) {
    val p = percent.coerceIn(0, 100)
    val color = when {
        p < 30 -> Color(0xFF3DDC84)
        p <= 50 -> Color(0xFFFFB020)
        else -> Color(0xFFFF4D4D)
    }
    val animate = animationsEnabled()
    val level by animateFloatAsState(p / 100f, if (animate) tween(900) else snap(), label = "humidity")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(width = 12.dp, height = 16.dp)) {
            val w = size.width
            val h = size.height
            // Drop: point at the top, round at the bottom.
            val drop = Path().apply {
                moveTo(w / 2, 0f)
                cubicTo(w * 0.15f, h * 0.38f, 0f, h * 0.55f, 0f, h * 0.68f)
                cubicTo(0f, h * 0.88f, w * 0.22f, h, w / 2, h)
                cubicTo(w * 0.78f, h, w, h * 0.88f, w, h * 0.68f)
                cubicTo(w, h * 0.55f, w * 0.85f, h * 0.38f, w / 2, 0f)
                close()
            }
            clipPath(drop) {
                val top = h * (1f - level)
                drawRect(color, topLeft = Offset(0f, top), size = Size(w, h - top))
            }
            drawPath(drop, color, style = Stroke(width = 1.2.dp.toPx()))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "${p}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
