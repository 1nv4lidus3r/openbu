package org.cygnusx1.openbu.bambuddy

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

// ------------------------------------------------------------------ heat colors

private val HeatCold = Color(0xFF4FA3FF)
private val HeatWarming = Color(0xFFFF9F1C)
private val HeatReady = Color(0xFF3DDC84)
private val HeatMax = Color(0xFFFF4D4D)

/** Which way a heater is going, shown as an arrow (or a check) next to the reading. */
enum class HeatTrend { NONE, HEATING, READY, COOLING }

/** Highest temperatures each part of a model allows, for the red "at the limit" color. */
data class HeatLimits(val nozzle: Int, val bed: Int, val chamber: Int)

fun heatLimits(model: String): HeatLimits {
    val m = model.uppercase().replace(" ", "")
    return when {
        m.startsWith("H2") -> HeatLimits(nozzle = 350, bed = 120, chamber = 65)
        m.startsWith("X1E") -> HeatLimits(nozzle = 320, bed = 120, chamber = 60)
        m.startsWith("X1") -> HeatLimits(nozzle = 300, bed = 110, chamber = 60)
        m.startsWith("A1MINI") -> HeatLimits(nozzle = 300, bed = 80, chamber = 60)
        m.startsWith("P2") -> HeatLimits(nozzle = 300, bed = 110, chamber = 60)
        else -> HeatLimits(nozzle = 300, bed = 100, chamber = 60) // P1, A1
    }
}

private const val COLD_BELOW = 40.0 // a heater that's off reads as "cold" below this

/** Color and trend for one heater. [tolerance] is how close to the target counts as "at temperature". */
private fun heatState(current: Double?, target: Double?, max: Int, tolerance: Double, slope: Double): Pair<Color, HeatTrend> {
    val cur = current ?: return HeatCold to HeatTrend.NONE
    val tgt = target?.takeIf { it > 0 }
    val atLimit = (tgt != null && tgt >= max) || cur >= max - 3
    val (color, trend) = when {
        tgt != null && cur < tgt - tolerance -> HeatWarming to HeatTrend.HEATING
        tgt != null && cur <= tgt + tolerance -> HeatReady to HeatTrend.READY
        cur >= COLD_BELOW -> {
            // Heater off (or lowered) and still warm: fade from orange toward blue as it cools.
            val hot = (tgt ?: COLD_BELOW).coerceAtLeast(COLD_BELOW)
            val span = (max * 0.6 - hot).coerceAtLeast(20.0)
            val frac = ((cur - hot) / span).coerceIn(0.0, 1.0).toFloat()
            // With no target, only the readings themselves tell the direction (a chamber without
            // a heater warms up on its own during a print).
            val dir = when {
                slope > 0.5 -> HeatTrend.HEATING
                slope < -0.5 || tgt != null -> HeatTrend.COOLING
                else -> HeatTrend.NONE
            }
            lerp(HeatCold, HeatWarming.copy(alpha = 0.85f), 0.35f + 0.65f * frac) to dir
        }
        else -> HeatCold to HeatTrend.NONE
    }
    return (if (atLimit && trend != HeatTrend.COOLING) HeatMax else color) to trend
}

/** Change over the last few readings (about the last 15-20 seconds), smoothing out the wobble. */
@Composable
private fun rememberSlope(current: Double?): Double {
    val history = remember { ArrayDeque<Double>() }
    val slope = if (history.size >= 2 && current != null) current - history.first() else 0.0
    LaunchedEffect(current) {
        if (current != null) {
            history.addLast(current)
            while (history.size > 6) history.removeFirst()
        }
    }
    return slope
}

/**
 * A temperature tile: heater icon colored by heat, the reading, and an arrow for heating or
 * cooling (a check at temperature). [active] draws the white border for the nozzle in use.
 */
@Composable
fun HeatTile(
    label: String,
    icon: ImageVector,
    current: Double?,
    target: Double?,
    max: Int,
    tolerance: Double,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val (heatColor, trend) = heatState(current, target, max, tolerance, rememberSlope(current))
    val color by animateColorAsState(heatColor, tween(600), label = "heat")
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = lerp(MaterialTheme.colorScheme.surfaceContainerHighest, color, 0.10f),
        border = if (active) BorderStroke(2.dp, Color.White) else null,
        modifier = modifier.alpha(if (enabled) 1f else 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tempLabel(rolled(current), target), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val arrow = when (trend) {
                    HeatTrend.HEATING -> Icons.Filled.ArrowUpward
                    HeatTrend.COOLING -> Icons.Filled.ArrowDownward
                    HeatTrend.READY -> Icons.Filled.Check
                    HeatTrend.NONE -> null
                }
                if (arrow != null) {
                    Spacer(Modifier.width(2.dp))
                    Icon(arrow, contentDescription = trend.name.lowercase(), tint = color, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

private fun tempLabel(current: Double?, target: Double?): String {
    val c = current?.let { "${it.roundToInt()}°" } ?: "-"
    return if (target != null && target > 0) "$c/${target.roundToInt()}°" else c
}

// ------------------------------------------------------------------ fans

/** Turns per second: still at 0%, a steady half speed up to 50%, then faster up to full at 100%. */
private fun fanTurnsPerSecond(percent: Int?): Float {
    val p = (percent ?: 0).coerceIn(0, 100)
    return when {
        p == 0 -> 0f
        p <= 50 -> 0.75f
        else -> 0.75f + (p - 50) / 50f * 1.25f // up to 2 turns per second
    }
}

/**
 * A fan tile whose fan icon spins with the fan speed. Only the icon layer is redrawn while it
 * spins, frames stop entirely at 0%, and it stays still when Android's animations are off.
 */
@Composable
fun FanTile(label: String, percent: Int?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val animationsOff = !animationsEnabled()
    val speed by animateFloatAsState(
        if (animationsOff) 0f else fanTurnsPerSecond(percent),
        tween(900),
        label = "fanSpeed",
    )
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { speed > 0.01f }.distinctUntilChanged().collectLatest { spinning ->
            if (!spinning) return@collectLatest
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (last != 0L) angle.floatValue = (angle.floatValue + speed * 360f * (now - last) / 1e9f) % 360f
                    last = now
                }
            }
        }
    }
    val on = (percent ?: 0) > 0
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    PrinterIcons.Fan,
                    contentDescription = null,
                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = angle.floatValue },
                )
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            Text(rolled(percent?.toDouble())?.let { "${it.roundToInt()}%" } ?: "-", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ------------------------------------------------------------------ shared motion helpers

/**
 * How much the app moves (Settings > Appearance > Motion):
 * - FULL: everything, including the breathing glows and the attention effects;
 * - SUBTLE: only motion that shows information (fans, rolling numbers, filling bars and spools);
 * - OFF: nothing moves; colors, arrows and borders still show everything.
 */
enum class Motion { FULL, SUBTLE, OFF }

/** The Motion level for everything below it; set once at the top of the app. */
val LocalMotion = androidx.compose.runtime.staticCompositionLocalOf { Motion.FULL }

/** Saved choices for the Motion setting. "system" follows the phone's "Remove animations". */
object MotionPref {
    const val KEY = "motion"
    const val SYSTEM = "system"
    const val FULL = "full"
    const val SUBTLE = "subtle"
    const val OFF = "off"

    fun resolve(context: android.content.Context, pref: String?): Motion = when (pref) {
        FULL -> Motion.FULL
        SUBTLE -> Motion.SUBTLE
        OFF -> Motion.OFF
        else -> {
            val phoneOff = runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            }.getOrDefault(false)
            if (phoneOff) Motion.OFF else Motion.FULL
        }
    }
}

/** True unless Motion is Off: fans spin, numbers roll, bars and spools fill smoothly. */
@Composable
fun animationsEnabled(): Boolean = LocalMotion.current != Motion.OFF

/** Only with Motion on Full: breathing glows and other attention effects. */
@Composable
fun attentionAnimations(): Boolean = LocalMotion.current == Motion.FULL

/**
 * A number that rolls smoothly to each new value instead of jumping. The first value shows as is
 * (no count-up from zero); null stays null.
 */
@Composable
fun rolled(value: Double?, durationMs: Int = 800): Double? {
    if (value == null) return null
    val animate = animationsEnabled()
    // Created with the first real value, so it never counts up from zero.
    val anim = remember { androidx.compose.animation.core.Animatable(value.toFloat()) }
    LaunchedEffect(value) {
        if (animate) anim.animateTo(value.toFloat(), tween(durationMs)) else anim.snapTo(value.toFloat())
    }
    return anim.value.toDouble()
}

/** Circular progress around [content] (the plate picture, or the percent). */
@Composable
fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 84.dp,
    stroke: androidx.compose.ui.unit.Dp = 5.dp,
    content: @Composable () -> Unit,
) {
    val animate = animationsEnabled()
    val p by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        if (animate) tween(900) else androidx.compose.animation.core.snap(),
        label = "ring",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(size)
            .ringArc(p, color, track, stroke),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun Modifier.ringArc(p: Float, color: Color, track: Color, stroke: androidx.compose.ui.unit.Dp): Modifier =
    this.then(
        Modifier.drawBehind {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            val style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = style)
            if (p > 0f) drawArc(color, -90f, 360f * p, false, topLeft, arcSize, style = style)
        },
    )

/**
 * Soft halo around a printer card in its state color. [breathing] slowly pulses it (paused,
 * needs attention); otherwise it stays still. Null color: no glow.
 */
@Composable
fun Modifier.stateGlow(color: Color?, breathing: Boolean, corner: androidx.compose.ui.unit.Dp = 20.dp): Modifier {
    if (color == null) return this
    val pulse = if (breathing && attentionAnimations()) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "glow")
        val a by t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                tween(1600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "glowAlpha",
        )
        a
    } else {
        0.7f
    }
    return this.drawBehind {
        val ring = 1.2.dp.toPx()
        val r = corner.toPx()
        for (i in 1..7) {
            val grow = i * 1.4.dp.toPx()
            val fade = (1f - i / 8f)
            drawRoundRect(
                color = color.copy(alpha = 0.22f * fade * fade * pulse),
                topLeft = androidx.compose.ui.geometry.Offset(-grow, -grow),
                size = androidx.compose.ui.geometry.Size(size.width + 2 * grow, size.height + 2 * grow),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + grow, r + grow),
                style = androidx.compose.ui.graphics.drawscope.Stroke(ring * 1.6f),
            )
        }
    }
}
