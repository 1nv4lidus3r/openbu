package org.cygnusx1.openbu.bambuddy

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.ceil

/**
 * Geometry ready for the 3D viewer.
 * kind = "mesh" (triangles, 9 floats each), "lines" (segments, 6 floats each) or "error".
 */
class ModelData(
    val kind: String,
    val floats: FloatArray,
    val note: String,
    /** For "lines": one feature type per segment (see Model3mf.TYPE_*), used for colouring. */
    val types: ByteArray = ByteArray(0),
)

/**
 * Reads a .3mf / .gcode.3mf (a zip). Prefers the real model mesh, including Bambu/Orca files
 * that keep each object in a separate model file under 3D/Objects and place it with transforms. If the file only
 * holds sliced G-code, falls back to the extrusion paths (bottom layers kept in full, so a
 * face-down design is still readable from below).
 */
object Model3mf {

    private const val MAX_TRIANGLES = 600_000
    private const val MAX_SEGMENTS = 250_000
    private const val FULL_DETAIL_MM = 1.2 // keep every move in the first ~1.2 mm (the face of face-down prints)

    // Feature types, matching the colours in assets/viewer/index.html (same groups as Bambuddy's preview).
    private const val TYPE_WALL: Byte = 0
    private const val TYPE_SPARSE: Byte = 1
    private const val TYPE_SOLID: Byte = 2
    private const val TYPE_BRIDGE: Byte = 3
    private const val TYPE_SUPPORT: Byte = 4
    private const val TYPE_SKIRT: Byte = 5
    private const val TYPE_GAP: Byte = 6
    private const val TYPE_IRONING: Byte = 7
    private const val TYPE_PRIME: Byte = 8
    private const val TYPE_OTHER: Byte = 9
    private const val TYPE_SKIP: Byte = -1 // start/end G-code, purge lines

    /** "; FEATURE: Outer wall" (Bambu/Orca) or ";TYPE:External perimeter" (Prusa-style). */
    private fun featureType(name: String): Byte {
        val n = name.trim().lowercase()
        return when {
            "custom" in n -> TYPE_SKIP
            "bridge" in n -> TYPE_BRIDGE
            "support" in n -> TYPE_SUPPORT
            "wall" in n || "perimeter" in n -> TYPE_WALL
            "sparse" in n || n == "infill" || "internal infill" in n -> TYPE_SPARSE
            "solid" in n || "top surface" in n || "bottom surface" in n -> TYPE_SOLID
            "skirt" in n || "brim" in n -> TYPE_SKIRT
            "gap" in n -> TYPE_GAP
            "ironing" in n -> TYPE_IRONING
            "prime" in n || "wipe tower" in n -> TYPE_PRIME
            else -> TYPE_OTHER
        }
    }

    fun load(file: File): ModelData {
        return try {
            ZipFile(file).use { zip ->
                val mesh = loadMesh(zip)
                if (mesh != null && mesh.floats.isNotEmpty()) {
                    mesh
                } else {
                    val gcodeEntry = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.lowercase().endsWith(".gcode") }
                        .minByOrNull { it.name }
                    if (gcodeEntry != null) {
                        zip.getInputStream(gcodeEntry).use { loadGcode(it) }
                    } else {
                        ModelData("error", FloatArray(0), "This file has no 3D model or G-code inside.")
                    }
                }
            }
        } catch (e: Exception) {
            ModelData("error", FloatArray(0), "Couldn't read this file: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ mesh

    /** 3MF affine transform "m00 m01 m02 m10 m11 m12 m20 m21 m22 m30 m31 m32" (row-vector convention). */
    private class Xf(val m: DoubleArray) {
        fun apply(x: Double, y: Double, z: Double, out: DoubleArray) {
            out[0] = x * m[0] + y * m[3] + z * m[6] + m[9]
            out[1] = x * m[1] + y * m[4] + z * m[7] + m[10]
            out[2] = x * m[2] + y * m[5] + z * m[8] + m[11]
        }

        /** First this, then [parent]. */
        fun then(parent: Xf): Xf {
            val a = m
            val b = parent.m
            val r = DoubleArray(12)
            for (row in 0 until 3) for (col in 0 until 3) {
                r[row * 3 + col] = a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
            }
            for (col in 0 until 3) {
                r[9 + col] = a[9] * b[col] + a[10] * b[3 + col] + a[11] * b[6 + col] + b[9 + col]
            }
            return Xf(r)
        }

        companion object {
            val IDENTITY = Xf(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0))
            fun parse(s: String?): Xf {
                val n = s?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }
                return if (n != null && n.size == 12) Xf(n.toDoubleArray()) else IDENTITY
            }
        }
    }

    private class Component(val objectId: String, val path: String?, val xf: Xf)

    private class Obj(
        var vertices: DoubleArray? = null,
        var triangles: IntArray? = null,
        val components: MutableList<Component> = mutableListOf(),
    )

    private class ModelFile(val objects: Map<String, Obj>, val build: List<Pair<String, Xf>>)

    private fun local(name: String) = name.substringAfter(':')

    private fun parseModel(input: InputStream): ModelFile {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val objects = mutableMapOf<String, Obj>()
        val build = mutableListOf<Pair<String, Xf>>()
        var cur: Obj? = null
        var verts = DoubleArray(3 * 1024)
        var vCount = 0
        var tris = IntArray(3 * 1024)
        var tCount = 0

        fun attr(name: String): String? {
            for (i in 0 until p.attributeCount) if (local(p.getAttributeName(i)) == name) return p.getAttributeValue(i)
            return null
        }

        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "object" -> {
                        cur = Obj().also { o -> attr("id")?.let { objects[it] = o } }
                        vCount = 0
                        tCount = 0
                    }
                    "vertex" -> {
                        if (vCount + 3 > verts.size) verts = verts.copyOf(verts.size * 2)
                        verts[vCount++] = attr("x")?.toDoubleOrNull() ?: 0.0
                        verts[vCount++] = attr("y")?.toDoubleOrNull() ?: 0.0
                        verts[vCount++] = attr("z")?.toDoubleOrNull() ?: 0.0
                    }
                    "triangle" -> {
                        if (tCount + 3 > tris.size) tris = tris.copyOf(tris.size * 2)
                        tris[tCount++] = attr("v1")?.toIntOrNull() ?: 0
                        tris[tCount++] = attr("v2")?.toIntOrNull() ?: 0
                        tris[tCount++] = attr("v3")?.toIntOrNull() ?: 0
                    }
                    "component" -> cur?.components?.add(
                        Component(
                            objectId = attr("objectid") ?: "",
                            path = attr("path")?.trimStart('/'),
                            xf = Xf.parse(attr("transform")),
                        ),
                    )
                    "item" -> attr("objectid")?.let { build += it to Xf.parse(attr("transform")) }
                }
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "mesh" -> cur?.let {
                        it.vertices = verts.copyOf(vCount)
                        it.triangles = tris.copyOf(tCount)
                    }
                    "object" -> cur = null
                }
            }
        }
        return ModelFile(objects, build)
    }

    private fun loadMesh(zip: ZipFile): ModelData? {
        val entries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.lowercase().endsWith(".model") }
            .toList()
        if (entries.isEmpty()) return null
        val files = entries.associate { e -> e.name.trimStart('/') to zip.getInputStream(e).use { parseModel(it) } }
        val rootKey = files.keys.firstOrNull { it.equals("3D/3dmodel.model", ignoreCase = true) } ?: files.keys.first()
        val root = files.getValue(rootKey)

        var out = FloatArray(9 * 4096)
        var n = 0
        var truncated = false
        val tmp = DoubleArray(3)

        fun emit(fileKey: String, objectId: String, xf: Xf, depth: Int) {
            if (depth > 8 || truncated) return
            val obj = files[fileKey]?.objects?.get(objectId) ?: return
            val v = obj.vertices
            val t = obj.triangles
            if (v != null && t != null) {
                var i = 0
                while (i + 2 < t.size) {
                    if (n / 9 >= MAX_TRIANGLES) {
                        truncated = true
                        return
                    }
                    if (n + 9 > out.size) out = out.copyOf(out.size * 2)
                    // Skip triangles that point at missing vertices rather than misalign the rest.
                    val valid = (0 until 3).all { k -> t[i + k] >= 0 && t[i + k] * 3 + 2 < v.size }
                    if (valid) {
                        for (k in 0 until 3) {
                            val vi = t[i + k] * 3
                            xf.apply(v[vi], v[vi + 1], v[vi + 2], tmp)
                            out[n++] = tmp[0].toFloat()
                            out[n++] = tmp[1].toFloat()
                            out[n++] = tmp[2].toFloat()
                        }
                    }
                    i += 3
                }
            }
            for (c in obj.components) emit(c.path ?: fileKey, c.objectId, c.xf.then(xf), depth + 1)
        }

        if (root.build.isNotEmpty()) {
            root.build.forEach { (id, xf) -> emit(rootKey, id, xf, 0) }
        } else {
            root.objects.keys.forEach { emit(rootKey, it, Xf.IDENTITY, 0) }
        }
        n -= n % 9 // drop any partial triangle
        if (n == 0) return null
        val note = if (truncated) "Large model: showing the first ${MAX_TRIANGLES / 1000}k triangles" else ""
        return ModelData("mesh", out.copyOf(n), note)
    }

    // ------------------------------------------------------------------ G-code fallback

    private fun loadGcode(input: InputStream): ModelData {
        // Read once into memory-light segments: [x0,y0,z0,x1,y1,z1] for every extruding move.
        var seg = FloatArray(6 * 8192)
        var types = ByteArray(8192)
        var n = 0
        var type = TYPE_SKIP // until the first feature marker, we're in the start G-code
        var sawMarker = false
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var e = 0.0
        var relE = false
        var relXYZ = false
        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val t = raw.trimStart()
                if (t.startsWith(";")) {
                    val c = t.drop(1).trim()
                    when {
                        c.startsWith("FEATURE:", true) || c.startsWith("TYPE:", true) -> {
                            type = featureType(c.substringAfter(':'))
                            sawMarker = true
                        }
                    }
                    continue
                }
                val line = raw.substringBefore(';').trim()
                if (line.isEmpty()) continue
                val parts = line.split(' ')
                when (parts[0].uppercase()) {
                    "M82" -> relE = false
                    "M83" -> relE = true
                    "G90" -> relXYZ = false
                    "G91" -> relXYZ = true
                    "G92" -> parts.forEach { if (it.startsWith("E", true)) e = it.drop(1).toDoubleOrNull() ?: e }
                    "G0", "G1", "G2", "G3" -> {
                        var nx = x
                        var ny = y
                        var nz = z
                        var de = 0.0
                        for (pt in parts.drop(1)) {
                            if (pt.isEmpty()) continue
                            val v = pt.drop(1).toDoubleOrNull() ?: continue
                            when (pt[0].uppercaseChar()) {
                                'X' -> nx = if (relXYZ) x + v else v
                                'Y' -> ny = if (relXYZ) y + v else v
                                'Z' -> nz = if (relXYZ) z + v else v
                                'E' -> de = if (relE) v else v - e
                            }
                        }
                        if (!relE) e += de
                        if (de > 0 && (nx != x || ny != y)) {
                            if (n + 6 > seg.size) seg = seg.copyOf(seg.size * 2)
                            if (n / 6 >= types.size) types = types.copyOf(types.size * 2)
                            types[n / 6] = type
                            seg[n++] = x.toFloat(); seg[n++] = y.toFloat(); seg[n++] = z.toFloat()
                            seg[n++] = nx.toFloat(); seg[n++] = ny.toFloat(); seg[n++] = nz.toFloat()
                        }
                        x = nx; y = ny; z = nz
                    }
                }
            }
        }
        // Drop start/end G-code and purge lines when the file marks its features; otherwise keep everything.
        run {
            var w = 0
            for (i in 0 until n / 6) {
                var ty = types[i]
                if (ty == TYPE_SKIP) {
                    if (sawMarker) continue
                    ty = TYPE_OTHER
                }
                if (w != i * 6) System.arraycopy(seg, i * 6, seg, w, 6)
                types[w / 6] = ty
                w += 6
            }
            n = w
        }
        if (n == 0) return ModelData("error", FloatArray(0), "No model or print paths found in this file.")

        val total = n / 6
        if (total <= MAX_SEGMENTS) {
            return ModelData("lines", seg.copyOf(n), "No 3D model in this file: showing the print paths", types.copyOf(total))
        }
        // Too many moves: keep the bottom layers in full (the face), thin out the rest.
        var minZ = Float.MAX_VALUE
        for (i in 0 until total) minZ = minOf(minZ, seg[i * 6 + 2])
        val faceTop = minZ + FULL_DETAIL_MM
        var faceCount = 0
        for (i in 0 until total) if (seg[i * 6 + 2] <= faceTop) faceCount++
        val budget = (MAX_SEGMENTS - faceCount).coerceAtLeast(MAX_SEGMENTS / 5)
        val keepEvery = ceil((total - faceCount).toDouble() / budget).toInt().coerceAtLeast(1)
        val out = FloatArray(6 * (faceCount + (total - faceCount) / keepEvery + 1))
        val outTypes = ByteArray(out.size / 6)
        var m = 0
        var upper = 0
        for (i in 0 until total) {
            val keep = seg[i * 6 + 2] <= faceTop || (upper++ % keepEvery == 0)
            if (keep && m + 6 <= out.size) {
                System.arraycopy(seg, i * 6, out, m, 6)
                outTypes[m / 6] = types[i]
                m += 6
            }
        }
        return ModelData("lines", out.copyOf(m), "No 3D model in this file: showing the print paths (simplified)", outTypes.copyOf(m / 6))
    }
}
