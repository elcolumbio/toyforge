package com.kidtoy.io

import com.kidtoy.geometry.Mesh
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImportException(msg: String) : Exception(msg)

/** Reads STL (binary or ASCII) and OBJ into a triangle soup. */
object MeshImporter {
    const val MAX_TRIANGLES = 300_000
    const val MAX_BYTES = 60_000_000

    /** Reads at most [MAX_BYTES] (+1 to detect overflow) so a huge file can't exhaust memory before we look at it. */
    fun readLimited(input: java.io.InputStream, max: Int = MAX_BYTES): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n
            if (total > max) throw ImportException("File too big")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun read(fileName: String, bytes: ByteArray): Mesh {
        if (isZip(bytes)) {
            val (name, inner) = largestModelInZip(bytes) ?: throw ImportException("No STL/OBJ inside the zip")
            return read(name, inner)
        }
        if (bytes.isEmpty()) throw ImportException("The file is empty")
        val lower = fileName.lowercase()
        val raw = try {
            when {
                lower.endsWith(".obj") -> readObj(String(bytes, Charsets.UTF_8))
                lower.endsWith(".stl") -> readStl(bytes, zUp = true)
                else -> if (looksLikeObj(bytes)) readObj(String(bytes, Charsets.UTF_8)) else readStl(bytes, zUp = true)
            }
        } catch (e: ImportException) { throw e } catch (e: RuntimeException) {
            throw ImportException("Broken file")
        }
        val clean = cleanTriangles(raw)
        if (clean.isEmpty()) throw ImportException("No triangles found")
        return normalise(clean)
    }

    /** Drops triangles with NaN/infinite coordinates or zero area (broken exports are full of them). */
    fun cleanTriangles(p: FloatArray): FloatArray {
        val out = FloatArray(p.size); var o = 0
        var t = 0
        while (t + 9 <= p.size) {
            var ok = true
            for (k in 0 until 9) if (!p[t + k].isFinite() || kotlin.math.abs(p[t + k]) > 1e7f) { ok = false; break }
            if (ok) {
                val ax = p[t + 3] - p[t]; val ay = p[t + 4] - p[t + 1]; val az = p[t + 5] - p[t + 2]
                val bx = p[t + 6] - p[t]; val by = p[t + 7] - p[t + 1]; val bz = p[t + 8] - p[t + 2]
                val cx = ay * bz - az * by; val cy = az * bx - ax * bz; val cz = ax * by - ay * bx
                if (cx * cx + cy * cy + cz * cz > 0f) { System.arraycopy(p, t, out, o, 9); o += 9 }
            }
            t += 9
        }
        return out.copyOf(o)
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4

    /** Picks the largest .obj/.stl entry (museum packages ship model + textures + mtl). */
    private fun largestModelInZip(bytes: ByteArray): Pair<String, ByteArray>? {
        var best: Pair<String, ByteArray>? = null
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.lowercase()
                if (e.isDirectory || n.contains("..") || !(n.endsWith(".obj") || n.endsWith(".stl"))) continue
                val data = z.readBytes()
                if (data.size > 120_000_000) throw ImportException("Model too big")
                if (best == null || data.size > best!!.second.size) best = e.name to data
            }
        }
        return best
    }

    private fun looksLikeObj(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 2048), Charsets.US_ASCII)
        return Regex("(?m)^v\\s").containsMatchIn(head) && Regex("(?m)^f\\s").containsMatchIn(String(b, Charsets.US_ASCII))
    }

    fun readStl(bytes: ByteArray, zUp: Boolean): FloatArray {
        val out: FloatArray
        if (bytes.size >= 84) {
            val n = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val ascii = String(bytes, 0, minOf(bytes.size, 512), Charsets.US_ASCII).let { it.trimStart().startsWith("solid") && it.contains("facet") }
            // exact size, or (common in the wild) a few trailing bytes after the triangles
            if (n > 0 && !ascii && 84 + 50 * n <= bytes.size.toLong() && 84 + 50 * n >= bytes.size.toLong() - 4096) {
                if (n > MAX_TRIANGLES) throw ImportException("Model too big ($n triangles)")
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                out = FloatArray((n * 9).toInt())
                for (t in 0 until n.toInt()) {
                    bb.position(84 + t * 50 + 12)
                    for (k in 0 until 9) out[t * 9 + k] = bb.float
                }
                return if (zUp) zUpToYUp(out) else out
            }
        }
        // ASCII
        val text = String(bytes, Charsets.US_ASCII)
        val list = ArrayList<Float>()
        val re = Regex("vertex\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)")
        for (m in re.findAll(text)) {
            list.add(m.groupValues[1].toFloat()); list.add(m.groupValues[2].toFloat()); list.add(m.groupValues[3].toFloat())
            if (list.size / 9 > MAX_TRIANGLES) throw ImportException("Model too big")
        }
        val arr = list.toFloatArray().copyOf(list.size / 9 * 9)
        return if (zUp) zUpToYUp(arr) else arr
    }

    fun readObj(text: String): FloatArray {
        val vs = ArrayList<Float>()
        val out = ArrayList<Float>()
        for (line0 in text.lineSequence()) {
            val line = line0.trim()
            if (line.startsWith("v ")) {
                val p = line.split(Regex("\\s+"))
                if (p.size < 4) throw ImportException("Bad OBJ vertex")
                vs.add(p[1].toFloat()); vs.add(p[2].toFloat()); vs.add(p[3].toFloat())
            } else if (line.startsWith("f ")) {
                val idx = line.split(Regex("\\s+")).drop(1).filter { it.isNotEmpty() }.map {
                    val i = it.substringBefore('/').toIntOrNull() ?: throw ImportException("Bad OBJ face")
                    if (i < 0) vs.size / 3 + i else i - 1
                }
                for (k in 1 until idx.size - 1) for (i in intArrayOf(idx[0], idx[k], idx[k + 1])) {
                    if (i < 0 || i * 3 + 2 >= vs.size) throw ImportException("Bad OBJ face index")
                    out.add(vs[i * 3]); out.add(vs[i * 3 + 1]); out.add(vs[i * 3 + 2])
                }
                if (out.size / 9 > MAX_TRIANGLES) throw ImportException("Model too big")
            }
        }
        return out.toFloatArray()
    }

    private fun zUpToYUp(p: FloatArray): FloatArray {
        val r = FloatArray(p.size)
        var i = 0
        while (i < p.size) { r[i] = p[i]; r[i + 1] = p[i + 2]; r[i + 2] = -p[i + 1]; i += 3 }
        return r
    }

    /** Centres the model and scales it so the largest side is 1 (matching the unit primitives). */
    fun normalise(p: FloatArray): Mesh {
        val m = Mesh.flat(p)
        val b = m.bounds()
        val size = maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2])
        if (size <= 0f || size.isNaN()) throw ImportException("Model has no size")
        val cx = (b[0] + b[3]) / 2; val cy = (b[1] + b[4]) / 2; val cz = (b[2] + b[5]) / 2
        val q = FloatArray(p.size)
        var i = 0
        while (i < p.size) { q[i] = (p[i] - cx) / size; q[i + 1] = (p[i + 1] - cy) / size; q[i + 2] = (p[i + 2] - cz) / size; i += 3 }
        return Mesh.flat(q)
    }

    /** Aspect ratio of a normalised mesh: (w, h, d) where the largest is 1. */
    fun extents(m: Mesh): FloatArray { val b = m.bounds(); return floatArrayOf(b[3] - b[0], b[4] - b[1], b[5] - b[2]) }
}
