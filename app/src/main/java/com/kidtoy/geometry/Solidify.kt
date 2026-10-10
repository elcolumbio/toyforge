package com.kidtoy.geometry

import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType

/** Indexed, coloured triangle mesh ready for export (Y-up, millimetres). */
class SolidMesh(val vertices: FloatArray, val triangles: IntArray, val triangleColors: IntArray) {
    val triangleCount get() = triangles.size / 3
    val vertexCount get() = vertices.size / 3

    /** Number of undirected edges that are not shared by exactly two oppositely oriented triangles. */
    fun openEdgeCount(): Int {
        val directed = HashMap<Long, Int>()
        fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
        for (t in 0 until triangleCount) for (e in 0 until 3) {
            val a = triangles[t * 3 + e]; val b = triangles[t * 3 + (e + 1) % 3]
            directed.merge(key(a, b), 1, Int::plus)
        }
        var bad = 0
        for ((k, c) in directed) {
            val a = (k shr 32).toInt(); val b = (k and 0xffffffffL).toInt()
            if (c != 1 || directed[key(b, a)] != 1) bad++
        }
        return bad
    }

    /** Signed volume in mm^3 (positive when triangles face outwards). */
    fun volume(): Double {
        var v = 0.0
        for (t in 0 until triangleCount) {
            val a = triangles[t * 3] * 3; val b = triangles[t * 3 + 1] * 3; val c = triangles[t * 3 + 2] * 3
            val ax = vertices[a].toDouble(); val ay = vertices[a + 1].toDouble(); val az = vertices[a + 2].toDouble()
            val bx = vertices[b].toDouble(); val by = vertices[b + 1].toDouble(); val bz = vertices[b + 2].toDouble()
            val cx = vertices[c].toDouble(); val cy = vertices[c + 1].toDouble(); val cz = vertices[c + 2].toDouble()
            v += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)
        }
        return v / 6.0
    }
}

/**
 * Turns the scene into one printable solid:
 *  1. every part is transformed to world space,
 *  2. parts whose bounding boxes touch are fused with a CSG union (so there are no internal faces),
 *  3. vertices are welded and T-junctions left by the BSP splits are repaired so edges are shared.
 * Imported meshes are passed through as separate shells (they may not be closed, so CSG could break them).
 */
object Solidify {
    /**
     * [fusedGroups]: groups merged into one solid by CSG. [fallbackGroups]: groups where the CSG result failed
     * validation, exported instead as separate closed, overlapping shells (slicers merge those automatically).
     */
    class Result(val mesh: SolidMesh, val fusedGroups: Int, val passThroughShells: Int, val fallbackGroups: Int = 0, val looseShells: Int = 0,
                 val timedOut: Boolean = false)

    /**
     * With [fuse], CSG and repair work stop at the wall-clock budget: the whole result then falls back to separate
     * closed shells, taken from [onTimeout] when the caller already has them (no work is repeated), else rebuilt.
     */
    fun run(shapes: List<Shape>, meshFor: (Shape) -> Mesh, fuse: Boolean = true, timeBudgetMs: Long = 12_000,
            onTimeout: (() -> Result)? = null): Result {
        val started = System.currentTimeMillis()
        if (!fuse) return runInner(shapes, meshFor, false, started, Long.MAX_VALUE / 4)
        return try {
            Csg.withDeadline(started + timeBudgetMs) { runInner(shapes, meshFor, true, started, timeBudgetMs) }
        } catch (e: CsgTimeout) {
            if (DEBUG) println("SOLIDIFY timeout after ${System.currentTimeMillis() - started} ms -> separate shells")
            val shells = onTimeout?.invoke() ?: runInner(shapes, meshFor, false, started, Long.MAX_VALUE / 4)
            Result(shells.mesh, 0, shells.passThroughShells, fallbackGroups = 1, looseShells = 0, timedOut = true)
        }
    }

    private fun runInner(shapes: List<Shape>, meshFor: (Shape) -> Mesh, fuse: Boolean, started: Long, timeBudgetMs: Long): Result {
        val parts = shapes.map { s -> Triple(s, Transforms.toWorld(s, meshFor(s)), Transforms.worldBounds(s, meshFor(s))) }
        val csgParts = parts.filter { it.first.type != ShapeType.MESH }
        val rawParts = parts.filter { it.first.type == ShapeType.MESH }

        // union-find over touching bounding boxes
        val parent = IntArray(csgParts.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in csgParts.indices) for (j in i + 1 until csgParts.size) {
            if (overlap(csgParts[i].third, csgParts[j].third, 0.01f)) parent[find(i)] = find(j)
        }
        val groups = csgParts.indices.groupBy { find(it) }.values

        val pieces = ArrayList<SolidMesh>()
        var fused = 0; var fallbacks = 0; var looseShells = 0
        fun polys(i: Int) = Csg.fromMesh(csgParts[i].second, csgParts[i].first.color)
        fun shell(i: Int) = weldAndTriangulate(polys(i))
        for (g in groups) {
            if (g.size == 1 || !fuse || System.currentTimeMillis() - started > timeBudgetMs) { g.forEach { pieces.add(shell(it)) }; continue }
            // Incremental union, biggest part first. Each step is validated (closed + plausible volume);
            // a part that would break the solid, or that arrives after the time / size budget, stays a
            // separate closed shell (slicers merge overlapping shells of one object automatically).
            // Fast path: one-shot union of the whole group (local clipping only).
            val loose = fuseAllAtOnce(g, ::polys, { csgParts[it].first.color }, csgParts.map { it.third }, pieces, started + timeBudgetMs)
            if (loose >= 0) { fused++; looseShells += loose; continue }
            if (g.size > 6) { g.forEach { pieces.add(shell(it)) }; fallbacks++; continue }
            val order = g.sortedByDescending { val b = csgParts[it].third; (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]) }
            var acc: List<CsgPolygon> = polys(order[0])
            var accMesh = weldAndTriangulate(acc)
            var accVol = accMesh.volume()
            var members = 1
            val leftovers = ArrayList<Int>()
            for (k in 1 until order.size) {
                val i = order[k]
                if (System.currentTimeMillis() - started > timeBudgetMs || acc.size > MAX_POLYGONS) { leftovers.add(i); continue }
                val p = polys(i)
                val partVol = weldAndTriangulate(p).volume()
                val cand = runCatching { Csg.union(acc, p) }.getOrNull() // CsgTimeout -> null -> stays separate
                val candMesh = cand?.let { runCatching { weldAndTriangulate(it) }.getOrNull() }
                val v = candMesh?.volume() ?: -1.0
                val ok = candMesh != null && candMesh.openEdgeCount() == 0 &&
                    v <= (accVol + partVol) * 1.01 + 1.0 && v >= maxOf(accVol, partVol) * 0.99 - 1.0
                if (ok) { acc = cand!!; accMesh = candMesh!!; accVol = v; members++ }
                else { leftovers.add(i); if (DEBUG) println("SOLIDIFY skip ${csgParts[i].first.type} open=${candMesh?.openEdgeCount()} vol=$v") }
            }
            pieces.add(accMesh)
            for (i in leftovers) pieces.add(shell(i))
            if (members > 1) fused++
            if (leftovers.isNotEmpty()) fallbacks++
        }
        for (r in rawParts) pieces.add(weldAndTriangulate(Csg.fromMesh(r.second, r.first.color)))
        return Result(concat(pieces), fused, rawParts.size, fallbacks, looseShells)
    }

    /**
     * Fast path: one-shot union of a whole group (local clipping only). If the result has cracks, the smallest
     * part touching a crack is set aside as its own closed shell and the union is retried (a few times).
     * Returns the indices (into [g]) that were fused; set-aside parts are added to [pieces] as shells too.
     */
    private fun fuseAllAtOnce(g: List<Int>, polys: (Int) -> List<CsgPolygon>, colors: (Int) -> Int, boxes: List<FloatArray>,
                              pieces: MutableList<SolidMesh>, deadline: Long): Int {
        // polygons are tagged with their part index so cracks can be traced back to parts
        val local = g.indices.map { k -> polys(g[k]).map { CsgPolygon(it.vertices, k, it.plane) } }
        val vols = local.map { weldAndTriangulate(it).volume() }
        val members = g.indices.toMutableList()
        val setAside = ArrayList<Int>()
        for (attempt in 0 until 5) {
            if (members.size < 2) break
            val t0 = System.nanoTime()
            val u = runCatching { Csg.unionAll(members.map { local[it] }, members.map { boxes[g[it]] }) }.getOrNull() ?: break
            val t1 = System.nanoTime()
            val m = runCatching { weldAndTriangulate(u) }.getOrNull() ?: break
            if (DEBUG) println("SOLIDIFY union ${(t1 - t0) / 1_000_000} ms polys=${u.size} weld ${(System.nanoTime() - t1) / 1_000_000} ms")
            val v = m.volume()
            val open = openEdgeVertices(m)
            val sum = members.sumOf { vols[it] }; val max = members.maxOf { vols[it] }
            if (open.isEmpty() && v <= sum * 1.01 + 1.0 && v >= max * 0.99 - 1.0) {
                val recoloured = SolidMesh(m.vertices, m.triangles, IntArray(m.triangleColors.size) { colors(g[m.triangleColors[it]]) })
                pieces.add(recoloured)
                for (k in setAside) pieces.add(weldAndTriangulate(polys(g[k])))
                return setAside.size
            }
            if (DEBUG) println("SOLIDIFY unionAll attempt $attempt open=${open.size} vol=$v sum=$sum")
            if (open.isEmpty() || System.currentTimeMillis() > deadline) break
            val involved = HashSet<Int>()
            for (t in 0 until m.triangleCount) for (e in 0 until 3) if (m.triangles[t * 3 + e] in open) involved.add(m.triangleColors[t])
            // set aside every part touching a crack except the biggest one (usually the body)
            val cand = involved.filter { it in members }.sortedBy { vols[it] }
            if (cand.isEmpty()) break
            val victims = if (cand.size == 1) cand else cand.dropLast(1)
            members.removeAll(victims.toSet()); setAside.addAll(victims)
        }
        return -1
    }

    private fun openEdgeVertices(m: SolidMesh): Set<Int> {
        fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
        val d = HashMap<Long, Int>()
        Csg.tick()
        for (t in 0 until m.triangleCount) for (e in 0 until 3) d.merge(key(m.triangles[t * 3 + e], m.triangles[t * 3 + (e + 1) % 3]), 1, Int::plus)
        val out = HashSet<Int>()
        for ((k, c) in d) {
            val a = (k shr 32).toInt(); val b = (k and 0xffffffffL).toInt()
            if (c != 1 || d[key(b, a)] != 1) { out.add(a); out.add(b) }
        }
        return out
    }

    fun concat(parts: List<SolidMesh>): SolidMesh {
        val v = FloatArray(parts.sumOf { it.vertices.size }); val t = IntArray(parts.sumOf { it.triangles.size })
        val c = IntArray(parts.sumOf { it.triangleColors.size })
        var vo = 0; var to = 0; var co = 0
        for (p in parts) {
            p.vertices.copyInto(v, vo)
            val base = vo / 3
            for (i in p.triangles.indices) t[to + i] = p.triangles[i] + base
            p.triangleColors.copyInto(c, co)
            vo += p.vertices.size; to += p.triangles.size; co += p.triangleColors.size
        }
        return SolidMesh(v, t, c)
    }

    fun overlap(a: FloatArray, b: FloatArray, eps: Float) =
        a[0] <= b[3] + eps && b[0] <= a[3] + eps && a[1] <= b[4] + eps && b[1] <= a[4] + eps && a[2] <= b[5] + eps && b[2] <= a[5] + eps

    /** Vertices closer than this (mm) are merged; far below printer resolution (~0.05 mm). */
    const val WELD_TOLERANCE = 1e-3
    var DEBUG = false

    /** Beyond this many BSP polygons a union gets slow on a tablet; remaining parts stay separate shells. */
    var MAX_POLYGONS = 25_000

    fun weldAndTriangulate(polys: List<CsgPolygon>): SolidMesh {
        val verts = ArrayList<Double>()
        val cell = WELD_TOLERANCE * 2
        val grid = HashMap<Long, MutableList<Int>>()
        fun cellKey(x: Long, y: Long, z: Long) = ((x + 0x100000L) and 0x1fffffL) or (((y + 0x100000L) and 0x1fffffL) shl 21) or (((z + 0x100000L) and 0x1fffffL) shl 42)
        val tol2 = WELD_TOLERANCE * WELD_TOLERANCE
        fun id(v: CsgVec): Int {
            val cx = Math.floor(v.x / cell).toLong(); val cy = Math.floor(v.y / cell).toLong(); val cz = Math.floor(v.z / cell).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) for (dz in -1L..1L) {
                val list = grid[cellKey(cx + dx, cy + dy, cz + dz)] ?: continue
                for (i in list) {
                    val ex = verts[i * 3] - v.x; val ey = verts[i * 3 + 1] - v.y; val ez = verts[i * 3 + 2] - v.z
                    if (ex * ex + ey * ey + ez * ez <= tol2) return i
                }
            }
            verts.add(v.x); verts.add(v.y); verts.add(v.z)
            val i = verts.size / 3 - 1
            grid.getOrPut(cellKey(cx, cy, cz)) { ArrayList(2) }.add(i)
            return i
        }
        // polygons as welded index loops (drop repeated consecutive indices and zero-area slivers)
        val loops = ArrayList<MutableList<Int>>(); val tags = ArrayList<Int>()
        for (p in polys) {
            Csg.tick()
            val l = ArrayList<Int>()
            for (v in p.vertices) { val i = id(v); if (l.isEmpty() || l.last() != i) l.add(i) }
            while (l.size > 1 && l.first() == l.last()) l.removeAt(l.size - 1)
            if (l.size >= 3 && polygonArea(l, verts) > 1e-9) { loops.add(l); tags.add(p.tag) }
        }
        fixTJunctions(loops, verts)
        fillSliverHoles(loops, tags)

        val tris = ArrayList<Int>(); val colors = ArrayList<Int>()
        for ((li, l) in loops.withIndex()) {
            if (l.size == 3) { tris.addAll(l); colors.add(tags[li]); continue }
            // fan from the centroid: never degenerate even with collinear (T-junction) vertices
            var cx = 0.0; var cy = 0.0; var cz = 0.0
            for (i in l) { cx += verts[i * 3]; cy += verts[i * 3 + 1]; cz += verts[i * 3 + 2] }
            val n = l.size.toDouble()
            val hasCollinear = hasCollinearVertex(l, verts)
            if (!hasCollinear) {
                for (k in 1 until l.size - 1) { tris.add(l[0]); tris.add(l[k]); tris.add(l[k + 1]); colors.add(tags[li]) }
            } else {
                verts.add(cx / n); verts.add(cy / n); verts.add(cz / n)
                val c = verts.size / 3 - 1
                for (k in l.indices) { tris.add(c); tris.add(l[k]); tris.add(l[(k + 1) % l.size]); colors.add(tags[li]) }
            }
        }
        return SolidMesh(FloatArray(verts.size) { verts[it].toFloat() }, tris.toIntArray(), colors.toIntArray())
    }

    private fun polygonArea(l: List<Int>, v: List<Double>): Double {
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        val o = l[0] * 3
        for (k in 1 until l.size - 1) {
            val a = l[k] * 3; val b = l[k + 1] * 3
            val ux = v[a] - v[o]; val uy = v[a + 1] - v[o + 1]; val uz = v[a + 2] - v[o + 2]
            val wx = v[b] - v[o]; val wy = v[b + 1] - v[o + 1]; val wz = v[b + 2] - v[o + 2]
            nx += uy * wz - uz * wy; ny += uz * wx - ux * wz; nz += ux * wy - uy * wx
        }
        return 0.5 * kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
    }

    private fun hasCollinearVertex(l: List<Int>, v: List<Double>): Boolean {
        for (k in l.indices) {
            val a = l[(k + l.size - 1) % l.size]; val b = l[k]; val c = l[(k + 1) % l.size]
            val ux = v[b * 3] - v[a * 3]; val uy = v[b * 3 + 1] - v[a * 3 + 1]; val uz = v[b * 3 + 2] - v[a * 3 + 2]
            val wx = v[c * 3] - v[b * 3]; val wy = v[c * 3 + 1] - v[b * 3 + 1]; val wz = v[c * 3 + 2] - v[b * 3 + 2]
            val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
            val cross = kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
            val lens = kotlin.math.sqrt(ux * ux + uy * uy + uz * uz) * kotlin.math.sqrt(wx * wx + wy * wy + wz * wz)
            if (cross <= 1e-9 * lens + 1e-12) return true
        }
        return false
    }

    /**
     * Closes the tiny sliver holes that remain where CSG split points disagree by less than the weld tolerance:
     * unmatched edges are chained into cycles and each cycle becomes a new face. Holes larger than
     * [maxHoleEdges] edges are left alone (the caller then falls back to separate shells).
     */
    fun fillSliverHoles(loops: MutableList<MutableList<Int>>, tags: MutableList<Int>, maxHoleEdges: Int = 12) {
        fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
        val directed = HashMap<Long, Int>()
        val tagOf = HashMap<Long, Int>()
        for ((li, l) in loops.withIndex()) for (k in l.indices) {
            val e = key(l[k], l[(k + 1) % l.size]); directed.merge(e, 1, Int::plus); tagOf[e] = tags[li]
        }
        // the hole face must use each open edge a->b reversed, i.e. b->a
        val next = HashMap<Int, MutableList<Int>>()
        for ((e, _) in directed) {
            val a = (e shr 32).toInt(); val b = (e and 0xffffffffL).toInt()
            if (directed[key(b, a)] == null) next.getOrPut(b) { ArrayList() }.add(a)
        }
        while (next.isNotEmpty()) {
            val start = next.keys.first()
            val cycle = ArrayList<Int>(); var cur = start
            while (true) {
                val outs = next[cur] ?: break
                val n = outs.removeAt(outs.size - 1)
                if (outs.isEmpty()) next.remove(cur)
                cycle.add(cur); cur = n
                if (cur == start || cycle.size > maxHoleEdges) break
            }
            if (cur == start && cycle.size >= 3 && cycle.size <= maxHoleEdges) {
                loops.add(cycle)
                tags.add(tagOf[key(cycle[1], cycle[0])] ?: tags.firstOrNull() ?: 0)
            }
        }
    }

    /**
     * Inserts vertices that lie on the interior of an unmatched polygon edge into that edge,
     * so neighbouring faces share identical edges (closes the cracks CSG splitting leaves behind).
     */
    fun fixTJunctions(loops: MutableList<MutableList<Int>>, v: List<Double>) {
        repeat(3) {
            val directed = HashMap<Long, Int>()
            fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
            for (l in loops) for (k in l.indices) directed.merge(key(l[k], l[(k + 1) % l.size]), 1, Int::plus)
            val openVerts = HashSet<Int>()
            var anyOpen = false
            for (l in loops) for (k in l.indices) {
                val a = l[k]; val b = l[(k + 1) % l.size]
                if (directed[key(b, a)] == null) { openVerts.add(a); openVerts.add(b); anyOpen = true }
            }
            if (!anyOpen) return
            // candidates sorted by x: each open edge only looks at vertices inside its x range
            val candidates = openVerts.sortedBy { v[it * 3] }.toIntArray()
            val candX = DoubleArray(candidates.size) { v[candidates[it] * 3] }
            fun lowerBound(x: Double): Int { var lo = 0; var hi = candX.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (candX[m] < x) lo = m + 1 else hi = m }; return lo }
            var changed = false
            for (l in loops) {
                var k = 0
                while (k < l.size) {
                    Csg.tick()
                    val a = l[k]; val b = l[(k + 1) % l.size]
                    if (directed[key(b, a)] != null) { k++; continue }
                    val ax = v[a * 3]; val ay = v[a * 3 + 1]; val az = v[a * 3 + 2]
                    val dx = v[b * 3] - ax; val dy = v[b * 3 + 1] - ay; val dz = v[b * 3 + 2] - az
                    val len2 = dx * dx + dy * dy + dz * dz
                    if (len2 < 1e-18) { k++; continue }
                    val inserts = ArrayList<Pair<Double, Int>>()
                    val bx = v[b * 3]
                    val lo = lowerBound(minOf(ax, bx) - 1e-4); val hiX = maxOf(ax, bx) + 1e-4
                    val minY = minOf(ay, v[b * 3 + 1]) - 1e-4; val maxY = maxOf(ay, v[b * 3 + 1]) + 1e-4
                    val minZ = minOf(az, v[b * 3 + 2]) - 1e-4; val maxZ = maxOf(az, v[b * 3 + 2]) + 1e-4
                    for (ci in lo until candidates.size) {
                        if (candX[ci] > hiX) break
                        val c = candidates[ci]
                        if (c == a || c == b) continue
                        val cy = v[c * 3 + 1]; val cz = v[c * 3 + 2]
                        if (cy < minY || cy > maxY || cz < minZ || cz > maxZ) continue
                        val px = v[c * 3] - ax; val py = v[c * 3 + 1] - ay; val pz = v[c * 3 + 2] - az
                        val t = (px * dx + py * dy + pz * dz) / len2
                        if (t <= 1e-7 || t >= 1 - 1e-7) continue
                        val ex = px - t * dx; val ey = py - t * dy; val ez = pz - t * dz
                        if (ex * ex + ey * ey + ez * ez < 1e-8) inserts.add(t to c)
                    }
                    if (inserts.isEmpty()) { k++; continue }
                    inserts.sortBy { it.first }
                    l.addAll(k + 1, inserts.map { it.second })
                    changed = true
                    k += inserts.size + 1
                }
            }
            if (!changed) return
        }
    }
}
