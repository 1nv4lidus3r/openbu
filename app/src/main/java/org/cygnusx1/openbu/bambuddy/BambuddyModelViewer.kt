package org.cygnusx1.openbu.bambuddy

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Same groups and colours as Bambuddy's G-code preview. Index = type byte from Model3mf.
private val TYPE_NAMES = listOf(
    "Walls", "Sparse infill", "Solid infill", "Bridge / overhang", "Support",
    "Skirt / brim", "Gap fill", "Ironing", "Prime tower", "Other",
)
private val TYPE_COLORS = intArrayOf(
    0xFFF97316.toInt(), 0xFF3B5BDB.toInt(), 0xFF7DD3FC.toInt(), 0xFFEF4444.toInt(), 0xFFA855F7.toInt(),
    0xFF22C55E.toInt(), 0xFFFACC15.toInt(), 0xFF86EFAC.toInt(), 0xFF5EEAD4.toInt(), 0xFF9CA3AF.toInt(),
)

/** Geometry centred on its bounding box (still Z-up), plus what the renderer needs per item. */
private class Prepared(data: ModelData) {
    val kind = data.kind
    val p: FloatArray = data.floats.copyOf()
    val types: ByteArray = data.types
    val count: Int = if (kind == "mesh") p.size / 9 else p.size / 6
    val radius: Float
    val normals: FloatArray? // mesh: one unit normal per triangle
    val edges: FloatArray?   // mesh: sharp edges, 6 floats each
    val edgeFaces: IntArray? // mesh: the two faces of each edge (-1 if open)
    val usedTypes: List<Int>

    init {
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in p.indices) {
            val k = i % 3
            if (p[i] < mn[k]) mn[k] = p[i]
            if (p[i] > mx[k]) mx[k] = p[i]
        }
        val c = FloatArray(3) { (mn[it] + mx[it]) / 2 }
        for (i in p.indices) p[i] -= c[i % 3]
        radius = maxOf(hypot(hypot(mx[0] - mn[0], mx[1] - mn[1]), mx[2] - mn[2]) / 2, 1f)

        if (kind == "mesh") {
            val n = FloatArray(count * 3)
            for (t in 0 until count) {
                val o = t * 9
                val ax = p[o + 3] - p[o]; val ay = p[o + 4] - p[o + 1]; val az = p[o + 5] - p[o + 2]
                val bx = p[o + 6] - p[o]; val by = p[o + 7] - p[o + 1]; val bz = p[o + 8] - p[o + 2]
                var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
                val l = sqrt(nx * nx + ny * ny + nz * nz).let { if (it == 0f) 1f else it }
                nx /= l; ny /= l; nz /= l
                n[t * 3] = nx; n[t * 3 + 1] = ny; n[t * 3 + 2] = nz
            }
            normals = n
            if (count <= 150_000) {
                val (e, f) = sharpEdges(p, n, count)
                edges = e
                edgeFaces = f
            } else {
                edges = null
                edgeFaces = null
            }
            usedTypes = emptyList()
        } else {
            normals = null
            edges = null
            edgeFaces = null
            usedTypes = if (types.size == count) types.map { minOf(it.toInt(), 9).coerceAtLeast(0) }.distinct().sorted() else emptyList()
        }
    }

    /** Edges where neighbouring faces meet at more than ~25°, so details like raised text stay visible. */
    private fun sharpEdges(p: FloatArray, n: FloatArray, count: Int): Pair<FloatArray, IntArray> {
        val ids = HashMap<String, Int>()
        fun vid(o: Int): Int {
            val key = "${(p[o] * 1000).toInt()},${(p[o + 1] * 1000).toInt()},${(p[o + 2] * 1000).toInt()}"
            return ids.getOrPut(key) { ids.size }
        }
        val first = HashMap<Long, Int>()
        val second = HashMap<Long, Int>()
        val ends = HashMap<Long, Int>() // edge -> offset of its first vertex pair (triangle, corner)
        for (t in 0 until count) {
            val v = IntArray(3) { vid(t * 9 + it * 3) }
            for (k in 0 until 3) {
                val a = v[k]; val b = v[(k + 1) % 3]
                val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
                if (first.containsKey(key)) second[key] = t else {
                    first[key] = t
                    ends[key] = t * 3 + k
                }
            }
        }
        val out = ArrayList<Float>()
        val faces = ArrayList<Int>()
        val cosLimit = cos(25.0 * PI / 180).toFloat()
        for ((key, f1) in first) {
            val f2 = second[key] ?: -1
            val sharp = f2 < 0 || (n[f1 * 3] * n[f2 * 3] + n[f1 * 3 + 1] * n[f2 * 3 + 1] + n[f1 * 3 + 2] * n[f2 * 3 + 2]) < cosLimit
            if (!sharp) continue
            val tc = ends.getValue(key)
            val t = tc / 3
            val k = tc % 3
            val o1 = t * 9 + k * 3
            val o2 = t * 9 + ((k + 1) % 3) * 3
            out.add(p[o1]); out.add(p[o1 + 1]); out.add(p[o1 + 2])
            out.add(p[o2]); out.add(p[o2 + 1]); out.add(p[o2 + 2])
            faces.add(f1); faces.add(f2)
        }
        return out.toFloatArray() to faces.toIntArray()
    }
}

/** Orthographic orbit camera around the model centre (Z-up). */
private class View3(val yaw: Float, val pitch: Float) {
    // Direction from the model towards the camera, screen-right and screen-up axes.
    val dx = cos(pitch) * cos(yaw); val dy = cos(pitch) * sin(yaw); val dz = sin(pitch)
    val rx = -sin(yaw); val ry = cos(yaw); val rz = 0f
    val ux = dy * rz - dz * ry; val uy = dz * rx - dx * rz; val uz = dx * ry - dy * rx
}

/** Sortable key: depth (ascending = far first) in the high bits, item index in the low bits. */
private fun depthKey(depth: Float, index: Int): Long {
    val b = java.lang.Float.floatToRawIntBits(depth)
    val s = b xor ((b shr 31) or Int.MIN_VALUE)
    return ((s.toLong() and 0xFFFFFFFFL) shl 32) or index.toLong()
}

private fun render(
    c: NativeCanvas, m: Prepared, v: View3,
    scale: Float, cx: Float, cy: Float, width: Int, height: Int,
    linePaints: Array<Paint>,
) {
    val p = m.p
    if (m.kind == "lines") {
        val n = m.count
        val keys = LongArray(n)
        val byHeight = abs(v.dz) > 0.25f // flat layers: seen from above/below, layer height is the order
        for (i in 0 until n) {
            val o = i * 6
            val d = if (byHeight) {
                (p[o + 2] + p[o + 5]) * (if (v.dz > 0) 1f else -1f)
            } else {
                (p[o] + p[o + 3]) * v.dx + (p[o + 1] + p[o + 4]) * v.dy + (p[o + 2] + p[o + 5]) * v.dz
            }
            keys[i] = depthKey(d, i)
        }
        keys.sort()
        val buf = FloatArray(n * 4)
        var len = 0
        var cur = -1
        for (k in 0 until n) {
            val i = (keys[k] and 0xFFFFFFFFL).toInt()
            val ty = if (m.types.size == n) minOf(m.types[i].toInt(), 9).coerceAtLeast(0) else 9
            if (ty != cur) {
                if (len > 0) c.drawLines(buf, 0, len, linePaints[cur])
                len = 0
                cur = ty
            }
            val o = i * 6
            for (e in 0 until 2) {
                val x = p[o + e * 3]; val y = p[o + e * 3 + 1]; val z = p[o + e * 3 + 2]
                buf[len++] = cx + scale * (x * v.rx + y * v.ry + z * v.rz)
                buf[len++] = cy - scale * (x * v.ux + y * v.uy + z * v.uz)
            }
        }
        if (len > 0) c.drawLines(buf, 0, len, linePaints[cur])
        return
    }

    renderMesh(c, m, v, scale, cx, cy, width, height)
}

/**
 * Solid model: a small software rasteriser with a depth buffer, so hidden faces and hidden
 * outlines never show through (sorting big flat faces by distance can't guarantee that).
 * Renders at a reduced resolution and scales the result up to the view.
 */
private fun renderMesh(c: NativeCanvas, m: Prepared, v: View3, scale: Float, cx: Float, cy: Float, width: Int, height: Int) {
    val nrm = m.normals ?: return
    val p = m.p
    val k = 0.6f
    val rw = maxOf(1, (width * k).toInt())
    val rh = maxOf(1, (height * k).toInt())
    val color = IntArray(rw * rh) { 0xFF000000.toInt() }
    val zbuf = FloatArray(rw * rh) { -Float.MAX_VALUE }
    // Light from the viewer, a little above and to the left.
    val lx = 0.55f * v.dx + 0.35f * v.ux - 0.25f * v.rx
    val ly = 0.55f * v.dy + 0.35f * v.uy - 0.25f * v.ry
    val lz = 0.55f * v.dz + 0.35f * v.uz - 0.25f * v.rz
    val ll = sqrt(lx * lx + ly * ly + lz * lz)
    val sx = FloatArray(3)
    val sy = FloatArray(3)
    val sz = FloatArray(3)

    for (t in 0 until m.count) {
        if (nrm[t * 3] * v.dx + nrm[t * 3 + 1] * v.dy + nrm[t * 3 + 2] * v.dz <= 0f) continue // facing away
        val o = t * 9
        for (e in 0 until 3) {
            val x = p[o + e * 3]; val y = p[o + e * 3 + 1]; val z = p[o + e * 3 + 2]
            sx[e] = (cx + scale * (x * v.rx + y * v.ry + z * v.rz)) * k
            sy[e] = (cy - scale * (x * v.ux + y * v.uy + z * v.uz)) * k
            sz[e] = x * v.dx + y * v.dy + z * v.dz // larger = nearer
        }
        val area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0])
        if (abs(area) < 1e-6f) continue
        val minX = maxOf(0, minOf(sx[0], sx[1], sx[2]).toInt())
        val maxX = minOf(rw - 1, maxOf(sx[0], sx[1], sx[2]).toInt() + 1)
        val minY = maxOf(0, minOf(sy[0], sy[1], sy[2]).toInt())
        val maxY = minOf(rh - 1, maxOf(sy[0], sy[1], sy[2]).toInt() + 1)
        if (minX > maxX || minY > maxY) continue
        val shade = abs(nrm[t * 3] * lx + nrm[t * 3 + 1] * ly + nrm[t * 3 + 2] * lz) / ll
        val g = (70 + 170 * shade).toInt().coerceIn(0, 255)
        val col = (0xFF shl 24) or (g shl 16) or ((g * 1.02f).toInt().coerceAtMost(255) shl 8) or (g * 1.08f).toInt().coerceAtMost(255)
        for (py in minY..maxY) {
            val fy = py + 0.5f
            for (px in minX..maxX) {
                val fx = px + 0.5f
                val w0 = ((sx[1] - fx) * (sy[2] - fy) - (sx[2] - fx) * (sy[1] - fy)) / area
                val w1 = ((sx[2] - fx) * (sy[0] - fy) - (sx[0] - fx) * (sy[2] - fy)) / area
                val w2 = 1f - w0 - w1
                if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue
                val z = w0 * sz[0] + w1 * sz[1] + w2 * sz[2]
                val i = py * rw + px
                if (z > zbuf[i]) {
                    zbuf[i] = z
                    color[i] = col
                }
            }
        }
    }

    // Outline edges, depth-tested so only visible ones show.
    val edges = m.edges
    val faces = m.edgeFaces
    if (edges != null && faces != null) {
        val bias = m.radius * 0.01f
        val edgeColor = 0xFF1E3A8A.toInt()
        for (e in 0 until edges.size / 6) {
            val front = (0 until 2).any { s ->
                val f = faces[e * 2 + s]
                f >= 0 && nrm[f * 3] * v.dx + nrm[f * 3 + 1] * v.dy + nrm[f * 3 + 2] * v.dz > 0f
            }
            if (!front) continue
            val o = e * 6
            for (q in 0 until 2) {
                val x = edges[o + q * 3]; val y = edges[o + q * 3 + 1]; val z = edges[o + q * 3 + 2]
                sx[q] = (cx + scale * (x * v.rx + y * v.ry + z * v.rz)) * k
                sy[q] = (cy - scale * (x * v.ux + y * v.uy + z * v.uz)) * k
                sz[q] = x * v.dx + y * v.dy + z * v.dz
            }
            val steps = maxOf(abs(sx[1] - sx[0]), abs(sy[1] - sy[0])).toInt() + 1
            for (st in 0..steps) {
                val f = st.toFloat() / steps
                val px = (sx[0] + (sx[1] - sx[0]) * f).toInt()
                val py = (sy[0] + (sy[1] - sy[0]) * f).toInt()
                if (px < 0 || py < 0 || px >= rw || py >= rh) continue
                val z = sz[0] + (sz[1] - sz[0]) * f
                val i = py * rw + px
                if (z >= zbuf[i] - bias) color[i] = edgeColor
            }
        }
    }

    val bmp = android.graphics.Bitmap.createBitmap(color, rw, rh, android.graphics.Bitmap.Config.ARGB_8888)
    c.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), BITMAP_PAINT)
}

private val BITMAP_PAINT = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

private enum class ViewPreset(val label: String, val yaw: Float, val pitch: Float) {
    ISO("Iso", (-PI / 3).toFloat(), (PI / 5).toFloat()),
    TOP("Top", (-PI / 2).toFloat(), (PI / 2).toFloat()),
    BOTTOM("Bottom", (-PI / 2).toFloat(), (-PI / 2).toFloat()),
    FRONT("Front", (-PI / 2).toFloat(), 0f),
    BACK("Back", (PI / 2).toFloat(), 0f),
}

/**
 * Full-screen 3D view of a .3mf on the printer, drawn natively: drag to rotate, pinch to zoom,
 * two fingers to move, plus Iso / Top / Bottom / Front / Back (Bottom shows the face of
 * face-down prints).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Model3DScreen(printerId: Int, file: BbFile, vm: BambuddyViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var prepared by remember { mutableStateOf<Prepared?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Downloading ${file.name}...") }

    BackHandler(onBack = onBack)

    LaunchedEffect(file.path) {
        try {
            val data = withContext(Dispatchers.IO) {
                val src = vm.files(printerId) ?: throw IllegalStateException("No connection to this printer")
                val tmp = File(context.cacheDir, "bammate_view.3mf")
                val (stream, _) = src.open(file.path)
                stream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                withContext(Dispatchers.Main) { status = "Reading the model..." }
                val d = Model3mf.load(tmp)
                tmp.delete()
                d
            }
            note = data.note
            if (data.kind != "error") {
                prepared = withContext(Dispatchers.Default) { Prepared(data) }
            } else {
                status = data.note
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Couldn't load the file: " + vm.describe(e)
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding),
        ) {
            val m = prepared
            if (m == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!status.startsWith("Couldn't") && note == null) LinearProgressIndicator()
                    Text(
                        status,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                ModelCanvas(m, note)
            }
        }
    }
}

@Composable
private fun ModelCanvas(m: Prepared, note: String?) {
    var yaw by remember { mutableFloatStateOf(ViewPreset.ISO.yaw) }
    var pitch by remember { mutableFloatStateOf(ViewPreset.ISO.pitch) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var preset by remember { mutableStateOf<ViewPreset?>(ViewPreset.ISO) }

    val linePaints = remember {
        Array(TYPE_COLORS.size) { i ->
            Paint().apply {
                color = TYPE_COLORS[i]
                strokeWidth = 2.2f
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // One finger rotates; two fingers pinch to zoom and move.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var prev: List<Offset> = emptyList()
                        while (true) {
                            val event = awaitPointerEvent()
                            val pts = event.changes.filter { it.pressed }.map { it.position }
                            if (pts.isEmpty()) break
                            if (pts.size == prev.size) {
                                if (pts.size == 1) {
                                    val d = pts[0] - prev[0]
                                    yaw -= d.x * 0.008f
                                    pitch = (pitch + d.y * 0.008f).coerceIn((-PI / 2).toFloat(), (PI / 2).toFloat())
                                    preset = null
                                } else {
                                    val c = Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
                                    val pc = Offset(prev.map { it.x }.average().toFloat(), prev.map { it.y }.average().toFloat())
                                    panX += c.x - pc.x
                                    panY += c.y - pc.y
                                    val dist = (pts[0] - pts[1]).getDistance()
                                    val pdist = (prev[0] - prev[1]).getDistance()
                                    if (pdist > 1f) zoom = (zoom * dist / pdist).coerceIn(0.2f, 40f)
                                }
                            }
                            prev = pts
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            val base = 0.92f * min(size.width, size.height) / (2 * m.radius)
            val view = View3(yaw, pitch)
            drawIntoCanvas { c ->
                render(
                    c.nativeCanvas, m, view,
                    scale = base * zoom,
                    cx = size.width / 2 + panX,
                    cy = size.height / 2 + panY,
                    width = size.width.toInt(),
                    height = size.height.toInt(),
                    linePaints = linePaints,
                )
            }
        }

        // Note (e.g. "showing the print paths") and the colour legend.
        Column(modifier = Modifier.padding(12.dp)) {
            if (!note.isNullOrBlank()) {
                Text(
                    note,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(8.dp))
            }
            if (m.usedTypes.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xD9111115)) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        m.usedTypes.forEach { t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(Color(TYPE_COLORS[t]), CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(TYPE_NAMES[t], style = MaterialTheme.typography.labelSmall, color = Color(0xFFD1D5DB))
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ViewPreset.entries.forEach { pv ->
                FilterChip(
                    selected = preset == pv,
                    onClick = {
                        yaw = pv.yaw
                        pitch = pv.pitch
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                        preset = pv
                    },
                    label = { Text(pv.label) },
                )
            }
        }
    }
}
