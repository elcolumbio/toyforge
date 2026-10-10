package com.kidtoy.text

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A tiny, read-only TrueType ('glyf') reader: just enough to turn characters into outlines.
 * Supports cmap formats 4 and 12, simple and composite glyphs, quadratic curves.
 * Kerning is ignored (letters are spaced by their advance widths plus [Text3D]'s extra gap).
 */
class TrueTypeFont(bytes: ByteArray) {
    private val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    private val tables = HashMap<String, Int>()
    val unitsPerEm: Int
    val ascent: Int
    val descent: Int
    private val numGlyphs: Int
    private val longLoca: Boolean
    private val numHMetrics: Int
    private val cmap = HashMap<Int, Int>()

    init {
        val numTables = u16(4)
        for (i in 0 until numTables) {
            val rec = 12 + 16 * i
            val tag = String(CharArray(4) { (b.get(rec + it).toInt() and 0xff).toChar() })
            tables[tag] = b.getInt(rec + 8)
        }
        require(tables.containsKey("glyf")) { "Only TrueType outline fonts are supported" }
        val head = t("head")
        unitsPerEm = u16(head + 18)
        longLoca = s16(head + 50) != 0
        numGlyphs = u16(t("maxp") + 4)
        val hhea = t("hhea")
        ascent = s16(hhea + 4); descent = s16(hhea + 6)
        numHMetrics = u16(hhea + 34)
        readCmap()
    }

    private fun t(tag: String) = tables[tag] ?: error("missing table $tag")
    private fun u16(o: Int) = b.getShort(o).toInt() and 0xffff
    private fun s16(o: Int) = b.getShort(o).toInt()
    private fun u32(o: Int) = b.getInt(o).toLong() and 0xffffffffL

    private fun readCmap() {
        val base = t("cmap")
        val n = u16(base + 2)
        var best = -1; var bestScore = -1
        for (i in 0 until n) {
            val pid = u16(base + 4 + i * 8); val eid = u16(base + 6 + i * 8)
            val off = base + u32(base + 8 + i * 8).toInt()
            val fmt = u16(off)
            val score = when {
                fmt == 12 && (pid == 3 && eid == 10 || pid == 0) -> 3
                fmt == 4 && (pid == 3 && eid == 1 || pid == 0) -> 2
                else -> -1
            }
            if (score > bestScore) { bestScore = score; best = off }
        }
        require(best >= 0) { "No unicode cmap" }
        when (u16(best)) {
            4 -> {
                val segX2 = u16(best + 6)
                val ends = best + 14
                val starts = ends + segX2 + 2
                val deltas = starts + segX2
                val ranges = deltas + segX2
                for (s in 0 until segX2 / 2) {
                    val end = u16(ends + s * 2); val start = u16(starts + s * 2)
                    val delta = s16(deltas + s * 2); val ro = u16(ranges + s * 2)
                    if (start == 0xffff) continue
                    for (c in start..end) {
                        val g = if (ro == 0) (c + delta) and 0xffff else {
                            val gi = u16(ranges + s * 2 + ro + (c - start) * 2)
                            if (gi == 0) 0 else (gi + delta) and 0xffff
                        }
                        if (g != 0) cmap[c] = g
                    }
                }
            }
            12 -> {
                val groups = u32(best + 12).toInt()
                for (g in 0 until groups) {
                    val o = best + 16 + g * 12
                    val sc = u32(o).toInt(); val ec = u32(o + 4).toInt(); val sg = u32(o + 8).toInt()
                    for (c in sc..ec) cmap[c] = sg + (c - sc)
                }
            }
        }
    }

    fun hasGlyph(codePoint: Int) = cmap.containsKey(codePoint)
    fun glyphIndex(codePoint: Int) = cmap[codePoint] ?: 0

    fun advance(glyph: Int): Int {
        val hmtx = t("hmtx")
        val i = if (glyph < numHMetrics) glyph else numHMetrics - 1
        return u16(hmtx + i * 4)
    }

    private fun glyphOffset(g: Int): Pair<Int, Int> {
        val loca = t("loca"); val glyf = t("glyf")
        return if (longLoca) (glyf + u32(loca + g * 4).toInt()) to (glyf + u32(loca + g * 4 + 4).toInt())
        else (glyf + u16(loca + g * 2) * 2) to (glyf + u16(loca + g * 2 + 2) * 2)
    }

    /** Closed contours in font units (y up), curves flattened with [curveSteps] segments per quadratic. */
    fun contours(glyph: Int, curveSteps: Int = 6, depth: Int = 0): List<List<DoubleArray>> {
        if (glyph < 0 || glyph >= numGlyphs || depth > 4) return emptyList()
        val (start, end) = glyphOffset(glyph)
        if (end <= start) return emptyList()
        val nc = s16(start)
        return if (nc >= 0) simple(start, nc, curveSteps) else composite(start, curveSteps, depth)
    }

    private fun simple(start: Int, nc: Int, steps: Int): List<List<DoubleArray>> {
        val endPts = IntArray(nc) { u16(start + 10 + it * 2) }
        val nPts = if (nc == 0) 0 else endPts.last() + 1
        val insLen = u16(start + 10 + nc * 2)
        var p = start + 12 + nc * 2 + insLen
        val flags = IntArray(nPts)
        var i = 0
        while (i < nPts) {
            val f = b.get(p++).toInt() and 0xff
            flags[i++] = f
            if (f and 8 != 0) { var r = b.get(p++).toInt() and 0xff; while (r-- > 0 && i < nPts) flags[i++] = f }
        }
        val xs = IntArray(nPts); val ys = IntArray(nPts)
        var v = 0
        for (k in 0 until nPts) {
            val f = flags[k]
            v += when {
                f and 2 != 0 -> { val d = b.get(p++).toInt() and 0xff; if (f and 16 != 0) d else -d }
                f and 16 != 0 -> 0
                else -> { val d = s16(p); p += 2; d }
            }
            xs[k] = v
        }
        v = 0
        for (k in 0 until nPts) {
            val f = flags[k]
            v += when {
                f and 4 != 0 -> { val d = b.get(p++).toInt() and 0xff; if (f and 32 != 0) d else -d }
                f and 32 != 0 -> 0
                else -> { val d = s16(p); p += 2; d }
            }
            ys[k] = v
        }
        val out = ArrayList<List<DoubleArray>>()
        var s = 0
        for (c in 0 until nc) {
            val e = endPts[c]
            val pts = (s..e).map { Triple(xs[it].toDouble(), ys[it].toDouble(), flags[it] and 1 != 0) }
            s = e + 1
            if (pts.size >= 2) out.add(flatten(pts, steps))
        }
        return out
    }

    private fun flatten(pts: List<Triple<Double, Double, Boolean>>, steps: Int): List<DoubleArray> {
        // Expand implied on-curve points between consecutive off-curve points.
        val full = ArrayList<Triple<Double, Double, Boolean>>()
        for (k in pts.indices) {
            val a = pts[k]; val n = pts[(k + 1) % pts.size]
            full.add(a)
            if (!a.third && !n.third) full.add(Triple((a.first + n.first) / 2, (a.second + n.second) / 2, true))
        }
        val startIdx = full.indexOfFirst { it.third }.let { if (it < 0) 0 else it }
        val ring = (0 until full.size).map { full[(startIdx + it) % full.size] }
        val res = ArrayList<DoubleArray>()
        var k = 0
        while (k < ring.size) {
            val a = ring[k]
            res.add(doubleArrayOf(a.first, a.second))
            val n = ring[(k + 1) % ring.size]
            if (!n.third) {
                val c = ring[(k + 2) % ring.size]
                for (st in 1 until steps) {
                    val tt = st.toDouble() / steps; val mt = 1 - tt
                    res.add(doubleArrayOf(mt * mt * a.first + 2 * mt * tt * n.first + tt * tt * c.first,
                        mt * mt * a.second + 2 * mt * tt * n.second + tt * tt * c.second))
                }
                k += 2
            } else k += 1
        }
        return res
    }

    private fun composite(start: Int, steps: Int, depth: Int): List<List<DoubleArray>> {
        val out = ArrayList<List<DoubleArray>>()
        var p = start + 10
        while (true) {
            val flags = u16(p); val gi = u16(p + 2); p += 4
            val dx: Double; val dy: Double
            if (flags and 1 != 0) { dx = s16(p).toDouble(); dy = s16(p + 2).toDouble(); p += 4 }
            else { dx = b.get(p).toDouble(); dy = b.get(p + 1).toDouble(); p += 2 }
            var a = 1.0; var bb = 0.0; var c = 0.0; var d = 1.0
            fun f2(o: Int) = s16(o) / 16384.0
            when {
                flags and 8 != 0 -> { a = f2(p); d = a; p += 2 }
                flags and 0x40 != 0 -> { a = f2(p); d = f2(p + 2); p += 4 }
                flags and 0x80 != 0 -> { a = f2(p); bb = f2(p + 2); c = f2(p + 4); d = f2(p + 6); p += 8 }
            }
            for (ct in contours(gi, steps, depth + 1)) out.add(ct.map { q -> doubleArrayOf(a * q[0] + c * q[1] + dx, bb * q[0] + d * q[1] + dy) })
            if (flags and 0x20 == 0) break
        }
        return out
    }
}
