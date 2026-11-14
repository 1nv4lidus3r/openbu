package org.cygnusx1.openbu.bambuddy

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import kotlin.math.cos
import kotlin.math.sin

private val BulbYellow = Color(0xFFFACC15)
private val BulbAmber = Color(0xFFB45309)

/**
 * BamMate's light bulb. Off: a grey outline. On: filled warm yellow with rays and a soft glow
 * behind it. Switching fades between the two (instantly with Motion off).
 */
@Composable
fun LightBulb(on: Boolean, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val animate = animationsEnabled()
    val t by animateFloatAsState(if (on) 1f else 0f, if (animate) tween(350) else snap(), label = "bulb")
    val offColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2
        val glassR = w * 0.27f
        val glassCy = h * 0.40f
        // Soft glow behind the bulb.
        if (t > 0f) {
            drawCircle(
                Brush.radialGradient(
                    listOf(BulbYellow.copy(alpha = 0.45f * t), Color.Transparent),
                    center = Offset(cx, glassCy),
                    radius = w * 0.55f,
                ),
                radius = w * 0.55f,
                center = Offset(cx, glassCy),
            )
            // Rays around the top half.
            val ray = Stroke(width = w * 0.06f, cap = StrokeCap.Round)
            for (deg in listOf(-160, -125, -90, -55, -20)) {
                val a = Math.toRadians(deg.toDouble())
                val r1 = glassR + w * 0.08f
                val r2 = glassR + w * 0.17f
                drawLine(
                    BulbAmber.copy(alpha = t),
                    Offset(cx + (r1 * cos(a)).toFloat(), glassCy + (r1 * sin(a)).toFloat()),
                    Offset(cx + (r2 * cos(a)).toFloat(), glassCy + (r2 * sin(a)).toFloat()),
                    strokeWidth = ray.width,
                    cap = StrokeCap.Round,
                )
            }
        }
        // Glass: a round top narrowing into the neck.
        val neckTop = glassCy + glassR * 0.75f
        val neckHalf = glassR * 0.48f
        val glass = Path().apply {
            moveTo(cx - neckHalf, h * 0.70f)
            lineTo(cx - neckHalf, neckTop + h * 0.03f)
            cubicTo(cx - glassR * 0.75f, neckTop - h * 0.02f, cx - glassR, glassCy + glassR * 0.35f, cx - glassR, glassCy)
            cubicTo(cx - glassR, glassCy - glassR * 1.35f, cx + glassR, glassCy - glassR * 1.35f, cx + glassR, glassCy)
            cubicTo(cx + glassR, glassCy + glassR * 0.35f, cx + glassR * 0.75f, neckTop - h * 0.02f, cx + neckHalf, neckTop + h * 0.03f)
            lineTo(cx + neckHalf, h * 0.70f)
            close()
        }
        val outline = androidx.compose.ui.graphics.lerp(offColor, BulbAmber, t)
        if (t > 0f) drawPath(glass, BulbYellow.copy(alpha = t))
        drawPath(glass, outline, style = Stroke(width = w * 0.06f, join = StrokeJoin.Round))
        // Screw base: three short bands.
        val band = Stroke(width = w * 0.06f, cap = StrokeCap.Round)
        drawLine(outline, Offset(cx - neckHalf, h * 0.76f), Offset(cx + neckHalf, h * 0.76f), band.width, StrokeCap.Round)
        drawLine(outline, Offset(cx - neckHalf, h * 0.85f), Offset(cx + neckHalf, h * 0.85f), band.width, StrokeCap.Round)
        drawLine(outline, Offset(cx - neckHalf * 0.5f, h * 0.93f), Offset(cx + neckHalf * 0.5f, h * 0.93f), band.width, StrokeCap.Round)
    }
}

/**
 * A green check that draws itself once, then settles into a small badge: shown next to a
 * printer's name the first time a finished print is seen (only with Motion on Full).
 */
@Composable
fun FinishCheck(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val progress = remember { Animatable(0f) }
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1.15f, tween(220, easing = FastOutSlowInEasing))
        pop.animateTo(1f, tween(160))
        progress.animateTo(1f, tween(450, easing = LinearEasing))
    }
    val green = Color(0xFF22C55E)
    Canvas(modifier = modifier.size(size)) {
        val s = pop.value
        val r = this.size.minDimension / 2 * s
        drawCircle(green.copy(alpha = 0.2f), radius = r)
        drawCircle(green, radius = r, style = Stroke(width = 2.dp.toPx()))
        // The tick, drawn from left to right as progress runs.
        val w = this.size.width
        val p1 = Offset(w * 0.28f, w * 0.52f)
        val p2 = Offset(w * 0.44f, w * 0.68f)
        val p3 = Offset(w * 0.73f, w * 0.36f)
        val first = (progress.value / 0.4f).coerceIn(0f, 1f)
        val second = ((progress.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
        val stroke = 2.4.dp.toPx()
        if (first > 0f) drawLine(green, p1, p1 + (p2 - p1) * first, stroke, StrokeCap.Round)
        if (second > 0f) drawLine(green, p2, p2 + (p3 - p2) * second, stroke, StrokeCap.Round)
    }
}

/** Grey placeholder cards with a soft moving shine while the printers load (still with Motion off). */
@Composable
fun LoadingCards(count: Int = 2) {
    val animate = animationsEnabled()
    val base = MaterialTheme.colorScheme.surfaceContainer
    val shine = MaterialTheme.colorScheme.surfaceContainerHighest
    val x = if (animate) {
        val tr = rememberInfiniteTransition(label = "shimmer")
        tr.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
            label = "shimmerX",
        ).value
    } else {
        0.5f
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(base),
            ) {
                val w = size.width
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, shine.copy(alpha = 0.7f), Color.Transparent),
                        start = Offset(w * (x - 0.4f), 0f),
                        end = Offset(w * x, size.height),
                    ),
                )
                // Placeholder name and status lines.
                val bar = shine.copy(alpha = 0.6f)
                drawRoundRect(bar, Offset(20.dp.toPx(), 20.dp.toPx()), androidx.compose.ui.geometry.Size(w * 0.35f, 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                drawRoundRect(bar, Offset(20.dp.toPx(), 42.dp.toPx()), androidx.compose.ui.geometry.Size(w * 0.55f, 10.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
            }
        }
    }
}
