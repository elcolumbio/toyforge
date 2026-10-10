package com.kidtoy.text

import com.kidtoy.geometry.Mesh
import kotlin.math.abs

/**
 * Turns a word into a solid, watertight 3D mesh (raised letters).
 *
 * Pipeline: glyph outlines (TrueType) -> clean contours -> group holes with their outer contour ->
 * bridge holes into the outer ring -> ear-clip the caps -> extrude with side walls.
 * The resulting mesh is normalised into the app's unit box [-0.5, 0.5]^3 (letters face +Z, read along +X)
 * so that a Shape's scale is simply (width, height, thickness) in mm. [Built.aspect] = width / height.
 */
object Text3D {
    class Built(val mesh: Mesh, val aspect: Float, val glyphCount: Int)

    @Volatile var font: TrueTypeFont? = null
    private val cache = object : LinkedHashMap<String, Built>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Built>?) = size > 24
    }

    const val MAX_CHARS = 16

    /** Keeps letters, digits, spaces and a few friendly symbols the font has; trims to [MAX_CHARS]. */
    fun clean(text: String): String {
        val f = font
        val sb = StringBuilder()
        var i = 0
        while (i < text.length && sb.codePointCount(0, sb.length) < MAX_CHARS) {
            val cp = text.codePointAt(i); i += Character.charCount(cp)
            if (cp == ' '.code || (f?.hasGlyph(cp) ?: Character.isLetterOrDigit(cp))) sb.appendCodePoint(cp)
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }

    fun build(text: String): Built? {
        val f = font ?: return null
        val t = clean(text)
        if (t.isBlank()) return null
        synchronized(cache) { cache[t]?.let { return it } }
        val built = buildUncached(f, t) ?: return null
        synchronized(cache) { cache[t] = built }
        return built
    }

    fun buildUncached(f: TrueTypeFont, text: String): Built? {
        val polys = ArrayList<List<DoubleArray>>()
        var penX = 0.0
        val gap = f.unitsPerEm * 0.06 // a little extra tracking keeps small printed names readable
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); i += Character.charCount(cp)
            val g = f.glyphIndex(cp)
            for (c in f.contours(g)) polys.add(c.map { doubleArrayOf(it[0] + penX, it[1]) })
            if (cp != ' '.code) count++
            penX += f.advance(g) + gap
        }
        val regions = Polygons.regions(polys.map { Polygons.clean(it) }.filter { it.size >= 3 && abs(Polygons.area(it)) > 1.0 })
        if (regions.isEmpty()) return null
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (r in regions) for (p in r.outer) { minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0]); minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1]) }
        val w = maxX - minX; val h = maxY - minY
        fun nx(x: Double) = ((x - minX) / w - 0.5).toFloat()
        fun ny(y: Double) = ((y - minY) / h - 0.5).toFloat()

        val pos = ArrayList<Float>()
        fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float) {
            pos.add(ax); pos.add(ay); pos.add(az); pos.add(bx); pos.add(by); pos.add(bz); pos.add(cx); pos.add(cy); pos.add(cz)
        }
        for (r in regions) {
            val ring = Polygons.bridge(r.outer, r.holes)
            val tris = Polygons.earClip(ring)
            for (k in tris.indices step 3) {
                val a = ring[tris[k]]; val b = ring[tris[k + 1]]; val c = ring[tris[k + 2]]
                tri(nx(a[0]), ny(a[1]), 0.5f, nx(b[0]), ny(b[1]), 0.5f, nx(c[0]), ny(c[1]), 0.5f) // front, CCW from +Z
                tri(nx(a[0]), ny(a[1]), -0.5f, nx(c[0]), ny(c[1]), -0.5f, nx(b[0]), ny(b[1]), -0.5f) // back
            }
            // side walls: outer is CCW, holes CW, so "outside" is always to the right of each edge
            for (loop in listOf(r.outer) + r.holes) {
                for (k in loop.indices) {
                    val a = loop[k]; val b = loop[(k + 1) % loop.size]
                    val ax = nx(a[0]); val ay = ny(a[1]); val bx = nx(b[0]); val by = ny(b[1])
                    tri(ax, ay, -0.5f, bx, by, -0.5f, bx, by, 0.5f)
                    tri(ax, ay, -0.5f, bx, by, 0.5f, ax, ay, 0.5f)
                }
            }
        }
        return Built(Mesh.flat(pos.toFloatArray()), (w / h).toFloat(), count)
    }
}

/** Small 2D polygon toolkit (doubles; outer rings CCW, holes CW). */
object Polygons {
    class Region(val outer: List<DoubleArray>, val holes: List<List<DoubleArray>>)

    fun area(p: List<DoubleArray>): Double {
        var a = 0.0
        for (i in p.indices) { val q = p[i]; val r = p[(i + 1) % p.size]; a += q[0] * r[1] - r[0] * q[1] }
        return a / 2
    }

    /** Removes duplicate and collinear points. */
    fun clean(p: List<DoubleArray>): List<DoubleArray> {
        var pts = ArrayList<DoubleArray>()
        for (q in p) if (pts.isEmpty() || dist2(pts.last(), q) > 1e-6) pts.add(q)
        while (pts.size > 1 && dist2(pts.first(), pts.last()) <= 1e-6) pts.removeAt(pts.size - 1)
        var changed = true
        while (changed && pts.size >= 3) {
            changed = false
            val out = ArrayList<DoubleArray>()
            for (i in pts.indices) {
                val a = pts[(i - 1 + pts.size) % pts.size]; val b = pts[i]; val c = pts[(i + 1) % pts.size]
                val cr = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
                if (abs(cr) < 1e-9 * (1 + dist2(a, c))) { changed = true; continue }
                out.add(b)
            }
            pts = out
        }
        return pts
    }

    private fun dist2(a: DoubleArray, b: DoubleArray) = (a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1])

    fun pointInPolygon(x: Double, y: Double, p: List<DoubleArray>): Boolean {
        var inside = false
        var j = p.size - 1
        for (i in p.indices) {
            val a = p[i]; val b = p[j]
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside
            j = i
        }
        return inside
    }

    /** Even-odd nesting: contours inside an odd number of others are holes of the smallest enclosing outer. */
    fun regions(contours: List<List<DoubleArray>>): List<Region> {
        val n = contours.size
        val sample = contours.map { c ->
            // a point just inside the contour's first edge is a robust probe for containment tests
            c[0]
        }
        val depth = IntArray(n)
        val parent = IntArray(n) { -1 }
        val areas = contours.map { abs(area(it)) }
        for (i in 0 until n) {
            var best = -1
            for (j in 0 until n) {
                if (i == j || areas[j] <= areas[i]) continue
                if (pointInPolygon(sample[i][0], sample[i][1], contours[j])) {
                    depth[i]++
                    if (best < 0 || areas[j] < areas[best]) best = j
                }
            }
            parent[i] = best
        }
        val out = ArrayList<Region>()
        for (i in 0 until n) {
            if (depth[i] % 2 != 0) continue
            val outer = if (area(contours[i]) < 0) contours[i].reversed() else contours[i]
            val holes = (0 until n).filter { depth[it] % 2 == 1 && parent[it] == i }
                .map { if (area(contours[it]) > 0) contours[it].reversed() else contours[it] }
            out.add(Region(outer, holes))
        }
        return out
    }

    /** Merges holes into the outer ring with zero-width bridges (classic ear-clipping preparation). */
    fun bridge(outer: List<DoubleArray>, holes: List<List<DoubleArray>>): List<DoubleArray> {
        var ring = ArrayList(outer)
        val sorted = holes.sortedByDescending { h -> h.maxOf { it[0] } }
        for (h in sorted) {
            val mi = h.indices.maxByOrNull { h[it][0] }!!
            val m = h[mi]
            // nearest intersection of a ray towards +x with the current ring
            var bestX = Double.MAX_VALUE; var bestEdge = -1
            for (i in ring.indices) {
                val a = ring[i]; val b = ring[(i + 1) % ring.size]
                if (a[1] == b[1]) continue
                if (m[1] < minOf(a[1], b[1]) || m[1] > maxOf(a[1], b[1])) continue
                val x = a[0] + (m[1] - a[1]) * (b[0] - a[0]) / (b[1] - a[1])
                if (x >= m[0] && x < bestX) { bestX = x; bestEdge = i }
            }
            var pi: Int
            if (bestEdge < 0) {
                pi = ring.indices.minByOrNull { dist2(ring[it], m) }!!
            } else {
                val a = ring[bestEdge]; val b = ring[(bestEdge + 1) % ring.size]
                pi = if (a[0] > b[0]) bestEdge else (bestEdge + 1) % ring.size
                val p = ring[pi]
                val ix = bestX
                // any ring vertex inside triangle (m, (ix, my), p) blocks the view: take the one with the smallest angle
                var bestAng = Double.MAX_VALUE
                for (k in ring.indices) {
                    val q = ring[k]
                    if (k == pi) continue
                    if (q[0] < m[0]) continue
                    if (inTriangle(m[0], m[1], ix, m[1], p[0], p[1], q[0], q[1])) {
                        val ang = abs(q[1] - m[1]) / (q[0] - m[0] + 1e-12)
                        if (ang < bestAng || (ang == bestAng && q[0] < ring[pi][0])) { bestAng = ang; pi = k }
                    }
                }
            }
            val merged = ArrayList<DoubleArray>(ring.size + h.size + 2)
            for (k in 0..pi) merged.add(ring[k])
            for (k in 0..h.size) merged.add(h[(mi + k) % h.size])
            merged.add(ring[pi])
            for (k in pi + 1 until ring.size) merged.add(ring[k])
            ring = merged
        }
        return ring
    }

    private fun inTriangle(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val neg = d1 < 0 || d2 < 0 || d3 < 0
        val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    private fun strictlyInTriangle(a: DoubleArray, b: DoubleArray, c: DoubleArray, p: DoubleArray): Boolean {
        val d1 = cross(a, b, p); val d2 = cross(b, c, p); val d3 = cross(c, a, p)
        return d1 > 1e-12 && d2 > 1e-12 && d3 > 1e-12
    }

    private fun cross(a: DoubleArray, b: DoubleArray, c: DoubleArray) = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])

    /** Ear clipping for a CCW (weakly) simple ring. Returns triangle indices into [ring], CCW. */
    fun earClip(ring: List<DoubleArray>): IntArray {
        val idx = ArrayList((ring.indices).toList())
        val out = ArrayList<Int>()
        var guard = 0
        while (idx.size > 3 && guard < 100_000) {
            guard++
            var clipped = false
            for (k in idx.indices) {
                val i0 = idx[(k - 1 + idx.size) % idx.size]; val i1 = idx[k]; val i2 = idx[(k + 1) % idx.size]
                val a = ring[i0]; val b = ring[i1]; val c = ring[i2]
                if (cross(a, b, c) <= 1e-12) continue
                var ok = true
                for (j in idx) {
                    if (j == i0 || j == i1 || j == i2) continue
                    val p = ring[j]
                    if ((p[0] == a[0] && p[1] == a[1]) || (p[0] == b[0] && p[1] == b[1]) || (p[0] == c[0] && p[1] == c[1])) continue
                    if (strictlyInTriangle(a, b, c, p)) { ok = false; break }
                }
                if (ok) { out.add(i0); out.add(i1); out.add(i2); idx.removeAt(k); clipped = true; break }
            }
            if (!clipped) {
                // degenerate leftovers (collinear bridge points): drop the flattest vertex without emitting a triangle
                var worst = 0; var worstVal = Double.MAX_VALUE
                for (k in idx.indices) {
                    val v = abs(cross(ring[idx[(k - 1 + idx.size) % idx.size]], ring[idx[k]], ring[idx[(k + 1) % idx.size]]))
                    if (v < worstVal) { worstVal = v; worst = k }
                }
                val i0 = idx[(worst - 1 + idx.size) % idx.size]; val i1 = idx[worst]; val i2 = idx[(worst + 1) % idx.size]
                if (cross(ring[i0], ring[i1], ring[i2]) > 0) { out.add(i0); out.add(i1); out.add(i2) }
                idx.removeAt(worst)
            }
        }
        if (idx.size == 3 && cross(ring[idx[0]], ring[idx[1]], ring[idx[2]]) > 0) { out.addAll(idx) }
        return out.toIntArray()
    }
}
