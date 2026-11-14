package org.cygnusx1.openbu.bambuddy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Build plate size in mm (width x depth) per model, for the skip map. */
fun plateSize(model: String): Pair<Double, Double> {
    val m = model.uppercase().replace(" ", "")
    return when {
        m.startsWith("H2D") -> 350.0 to 320.0
        m.startsWith("H2S") -> 340.0 to 320.0
        m.startsWith("A1MINI") -> 180.0 to 180.0
        else -> 256.0 to 256.0 // X1, P1, P2, A1
    }
}

val ObjectGreen = Color(0xFF4CAF50)
val SkipRed = Color(0xFFEF4444)

/** Size used for objects that only have a centre point (Bambuddy reports no outline). */
private const val MARKER_MM = 22.0

/**
 * Top-down map of the plate (back at the top, like the slicer) with each object where it sits,
 * numbered like the list under it. Tap an object to mark or unmark it for skipping. Objects
 * already skipped are greyed out and can't be picked.
 */
@Composable
fun SkipMap(
    objects: List<BbObject>,
    selected: Set<Int>,
    model: String,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (bedW, bedH) = plateSize(model)
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val plate = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)

    fun rectOf(o: BbObject): DoubleArray? {
        val b = o.bbox
        if (b != null && b.size >= 4) return doubleArrayOf(b[0], b[1], b[2], b[3])
        val x = o.x ?: return null
        val y = o.y ?: return null
        val h = MARKER_MM / 2
        return doubleArrayOf(x - h, y - h, x + h, y + h)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio((bedW / bedH).toFloat())
            .pointerInput(objects) {
                detectTapGestures { tap ->
                    val mmX = tap.x / size.width * bedW
                    val mmY = (1 - tap.y / size.height) * bedH
                    // The smallest object under the finger wins (they can overlap), with a little slack.
                    val hit = objects.filter { !it.skipped }.mapNotNull { o -> rectOf(o)?.let { o to it } }
                        .filter { (_, r) -> mmX in (r[0] - 4)..(r[2] + 4) && mmY in (r[1] - 4)..(r[3] + 4) }
                        .minByOrNull { (_, r) -> (r[2] - r[0]) * (r[3] - r[1]) }
                    hit?.let { onToggle(it.first.id) }
                }
            },
    ) {
        val sx = size.width / bedW.toFloat()
        val sy = size.height / bedH.toFloat()
        // Plate with a 50 mm grid.
        drawRoundRect(plate, cornerRadius = CornerRadius(10.dp.toPx()))
        var g = 50.0
        while (g < bedW) {
            drawLine(grid, Offset((g * sx).toFloat(), 0f), Offset((g * sx).toFloat(), size.height))
            g += 50
        }
        g = 50.0
        while (g < bedH) {
            val y = size.height - (g * sy).toFloat()
            drawLine(grid, Offset(0f, y), Offset(size.width, y))
            g += 50
        }

        objects.forEachIndexed { i, o ->
            val r = rectOf(o) ?: return@forEachIndexed
            val left = (r[0] * sx).toFloat()
            val right = (r[2] * sx).toFloat()
            val top = size.height - (r[3] * sy).toFloat()
            val bottom = size.height - (r[1] * sy).toFloat()
            val tl = Offset(left, top)
            val sz = Size(right - left, bottom - top)
            val corner = CornerRadius(4.dp.toPx())
            val picked = o.id in selected
            when {
                o.skipped -> {
                    drawRoundRect(Color.Gray.copy(alpha = 0.25f), tl, sz, corner)
                    drawRoundRect(
                        Color.Gray, tl, sz, corner,
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                    )
                }
                picked -> {
                    drawRoundRect(SkipRed.copy(alpha = 0.75f), tl, sz, corner)
                    drawRoundRect(Color.White, tl, sz, corner, style = Stroke(2.dp.toPx()))
                }
                else -> {
                    drawRoundRect(ObjectGreen.copy(alpha = 0.85f), tl, sz, corner)
                }
            }
            // Number in the middle, matching the list; a cross over objects marked to skip.
            val c = Offset((left + right) / 2, (top + bottom) / 2)
            if (picked) {
                val d = minOf(sz.width, sz.height) * 0.3f
                drawLine(Color.White, c + Offset(-d, -d), c + Offset(d, d), 2.5.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White, c + Offset(-d, d), c + Offset(d, -d), 2.5.dp.toPx(), StrokeCap.Round)
            } else {
                val text = measurer.measure("${i + 1}", labelStyle)
                drawText(text, topLeft = c - Offset(text.size.width / 2f, text.size.height / 2f))
            }
        }
    }
}
