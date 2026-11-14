package org.cygnusx1.openbu.bambuddy

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * BamMate's own printer icons, drawn on a 24x24 grid like the Material icons so they size and
 * tint the same way. Filled shapes and strokes are both black here; Icon() tints them.
 */
object PrinterIcons {
    private val ink = SolidColor(Color.Black)

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) = path(fill = ink, pathBuilder = block)

    private fun ImageVector.Builder.stroke(width: Float = 2f, block: PathBuilder.() -> Unit) = path(
        stroke = ink,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t)
        lineTo(r - rad, t)
        quadTo(r, t, r, t + rad)
        lineTo(r, b - rad)
        quadTo(r, b, r - rad, b)
        lineTo(l + rad, b)
        quadTo(l, b, l, b - rad)
        lineTo(l, t + rad)
        quadTo(l, t, l + rad, t)
        close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, false, true, cx + r, cy)
        arcTo(r, r, 0f, false, true, cx - r, cy)
        close()
    }

    /** Hotend: cooling fins on top, the heater block, and a short nozzle tip. */
    val Nozzle: ImageVector by lazy {
        icon("Nozzle") {
            fill { roundRect(10.5f, 1.5f, 13.5f, 9f, 0.5f) } // heat break
            fill { roundRect(7f, 2.5f, 17f, 4f, 0.75f) } // cooling fins
            fill { roundRect(7f, 5.5f, 17f, 7f, 0.75f) }
            fill { roundRect(4.5f, 8.5f, 19.5f, 15.5f, 1.5f) } // heater block
            fill {
                moveTo(9f, 15f) // nozzle
                lineTo(15f, 15f)
                lineTo(13.4f, 18.5f)
                lineTo(10.6f, 18.5f)
                close()
            }
            fill { roundRect(11f, 18f, 13f, 20.5f, 0.6f) }
        }
    }

    /** Heated bed: a build plate with heat rising from it. */
    val Bed: ImageVector by lazy {
        icon("Bed") {
            fill { roundRect(2f, 15.5f, 22f, 19.5f, 1.5f) }
            fill { roundRect(4f, 19f, 6f, 21.5f, 0.5f) }
            fill { roundRect(18f, 19f, 20f, 21.5f, 0.5f) }
            for (x in listOf(7f, 12f, 17f)) {
                stroke(1.8f) {
                    moveTo(x, 12.5f)
                    quadTo(x - 2f, 10.5f, x, 8.5f)
                    quadTo(x + 2f, 6.5f, x, 4.5f)
                }
            }
        }
    }

    /** Enclosed chamber with a thermometer inside. */
    val Chamber: ImageVector by lazy {
        icon("Chamber") {
            stroke(2f) { roundRect(3f, 3f, 21f, 21f, 3f) }
            fill { roundRect(10.8f, 6.5f, 13.2f, 15f, 1.2f) }
            fill { circle(12f, 16f, 2.6f) }
        }
    }

    /** Four-blade fan. Spun by FanTile; the hub sits exactly at the center. */
    val Fan: ImageVector by lazy {
        icon("Fan") {
            fill { circle(12f, 12f, 2.2f) }
            for (k in 0 until 4) {
                val a = Math.toRadians(90.0 * k)
                fun px(x: Float, y: Float) = (12 + (x - 12) * cos(a) - (y - 12) * sin(a)).toFloat()
                fun py(x: Float, y: Float) = (12 + (x - 12) * sin(a) + (y - 12) * cos(a)).toFloat()
                fill {
                    moveTo(px(11f, 9.6f), py(11f, 9.6f))
                    curveTo(px(8.6f, 6.4f), py(8.6f, 6.4f), px(10f, 2.2f), py(10f, 2.2f), px(13.6f, 2.6f), py(13.6f, 2.6f))
                    curveTo(px(16.4f, 3f), py(16.4f, 3f), px(15.6f, 7.4f), py(15.6f, 7.4f), px(13.4f, 10f), py(13.4f, 10f))
                    close()
                }
            }
        }
    }
}
