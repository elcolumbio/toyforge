package com.kidtoy.geometry

/*
 * Constructive Solid Geometry on BSP trees.
 * Kotlin port of the algorithm from csg.js (https://github.com/evanw/csg.js).
 * Original work Copyright (c) 2011 Evan Wallace, MIT License; full notice in THIRD_PARTY_LICENSES.md.
 * Modifications Copyright (c) 2026 Florian Benkö, MIT License.
 * Used to fuse overlapping toy parts into a single solid for export.
 */
class CsgVec(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: CsgVec) = CsgVec(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: CsgVec) = CsgVec(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = CsgVec(x * s, y * s, z * s)
    operator fun unaryMinus() = CsgVec(-x, -y, -z)
    fun dot(o: CsgVec) = x * o.x + y * o.y + z * o.z
    fun cross(o: CsgVec) = CsgVec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = kotlin.math.sqrt(dot(this))
    fun unit(): CsgVec { val l = length(); return if (l < 1e-300) this else this * (1.0 / l) }
    fun lerp(o: CsgVec, t: Double) = this + (o - this) * t
}

class CsgPlane(val normal: CsgVec, val w: Double) {
    fun flipped() = CsgPlane(-normal, -w)

    fun splitPolygon(
        polygon: CsgPolygon,
        coplanarFront: MutableList<CsgPolygon>, coplanarBack: MutableList<CsgPolygon>,
        front: MutableList<CsgPolygon>, back: MutableList<CsgPolygon>,
    ) {
        var polygonType = 0
        val types = IntArray(polygon.vertices.size)
        for (i in polygon.vertices.indices) {
            val t = normal.dot(polygon.vertices[i]) - w
            val type = if (t < -EPSILON) BACK else if (t > EPSILON) FRONT else COPLANAR
            polygonType = polygonType or type
            types[i] = type
        }
        when (polygonType) {
            COPLANAR -> (if (normal.dot(polygon.plane.normal) > 0) coplanarFront else coplanarBack).add(polygon)
            FRONT -> front.add(polygon)
            BACK -> back.add(polygon)
            else -> {
                val f = ArrayList<CsgVec>(); val b = ArrayList<CsgVec>()
                val n = polygon.vertices.size
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val ti = types[i]; val tj = types[j]
                    val vi = polygon.vertices[i]; val vj = polygon.vertices[j]
                    if (ti != BACK) f.add(vi)
                    if (ti != FRONT) b.add(vi)
                    if ((ti or tj) == SPANNING) {
                        val t = (w - normal.dot(vi)) / normal.dot(vj - vi)
                        val v = vi.lerp(vj, t)
                        f.add(v); b.add(v)
                    }
                }
                if (f.size >= 3) front.add(CsgPolygon(f, polygon.tag, polygon.plane))
                if (b.size >= 3) back.add(CsgPolygon(b, polygon.tag, polygon.plane))
            }
        }
    }

    companion object {
        const val EPSILON = 1e-5
        const val COPLANAR = 0; const val FRONT = 1; const val BACK = 2; const val SPANNING = 3
        fun fromPoints(a: CsgVec, b: CsgVec, c: CsgVec): CsgPlane {
            val n = (b - a).cross(c - a).unit()
            return CsgPlane(n, n.dot(a))
        }
    }
}

/** Convex polygon; [tag] carries the colour of the part it came from. */
class CsgPolygon(val vertices: List<CsgVec>, val tag: Int, plane: CsgPlane? = null) {
    val plane: CsgPlane = plane ?: CsgPlane.fromPoints(vertices[0], vertices[1], vertices[2])
    fun flipped() = CsgPolygon(vertices.reversed(), tag, plane.flipped())
}

/** Thrown when a boolean operation runs past [Csg.deadline]; callers fall back to separate shells. */
class CsgTimeout : RuntimeException("CSG time budget exceeded")

class CsgNode(polygons: List<CsgPolygon>? = null) {
    private var plane: CsgPlane? = null
    private var front: CsgNode? = null
    private var back: CsgNode? = null
    private var polygons = ArrayList<CsgPolygon>()

    init { if (polygons != null) build(polygons) }

    fun invert() {
        // iterative traversal to avoid deep recursion
        val stack = ArrayDeque<CsgNode>(); stack.add(this)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            for (i in n.polygons.indices) n.polygons[i] = n.polygons[i].flipped()
            n.plane = n.plane?.flipped()
            val tmp = n.front; n.front = n.back; n.back = tmp
            n.front?.let { stack.add(it) }; n.back?.let { stack.add(it) }
        }
    }

    /** Removes the parts of [polys] that are inside this BSP solid (iterative to avoid deep recursion). */
    fun clipPolygons(polys: List<CsgPolygon>): List<CsgPolygon> {
        val out = ArrayList<CsgPolygon>()
        val stack = ArrayDeque<Pair<CsgNode, List<CsgPolygon>>>()
        stack.add(this to polys)
        while (stack.isNotEmpty()) {
            Csg.tick()
            val (n, list) = stack.removeLast()
            val p = n.plane
            if (p == null) { out.addAll(list); continue }
            val f = ArrayList<CsgPolygon>(); val b = ArrayList<CsgPolygon>()
            for (poly in list) p.splitPolygon(poly, f, b, f, b)
            val fr = n.front; val bk = n.back
            if (fr != null) stack.add(fr to f) else out.addAll(f)
            if (bk != null) stack.add(bk to b) // polygons behind a leaf are inside: dropped
        }
        return out
    }

    fun clipTo(bsp: CsgNode) {
        val stack = ArrayDeque<CsgNode>(); stack.add(this)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            n.polygons = ArrayList(bsp.clipPolygons(n.polygons))
            n.front?.let { stack.add(it) }; n.back?.let { stack.add(it) }
        }
    }

    fun allPolygons(): List<CsgPolygon> {
        val out = ArrayList<CsgPolygon>()
        val stack = ArrayDeque<CsgNode>(); stack.add(this)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out.addAll(n.polygons)
            n.front?.let { stack.add(it) }; n.back?.let { stack.add(it) }
        }
        return out
    }

    fun build(polys: List<CsgPolygon>) {
        val work = ArrayDeque<Pair<CsgNode, List<CsgPolygon>>>()
        work.add(this to polys)
        while (work.isNotEmpty()) {
            Csg.tick()
            val (node, list) = work.removeLast()
            if (list.isEmpty()) continue
            val p = node.plane ?: list[0].plane.also { node.plane = it }
            val f = ArrayList<CsgPolygon>(); val b = ArrayList<CsgPolygon>()
            for (poly in list) p.splitPolygon(poly, node.polygons, node.polygons, f, b)
            if (f.isNotEmpty()) { val c = node.front ?: CsgNode().also { node.front = it }; work.add(c to f) }
            if (b.isNotEmpty()) { val c = node.back ?: CsgNode().also { node.back = it }; work.add(c to b) }
        }
    }
}

object Csg {
    private val deadlineTl = ThreadLocal<Long>()
    private val ticks = ThreadLocal<IntArray>()

    /** Runs [block] with a wall-clock deadline (ms since epoch); any CSG call inside throws [CsgTimeout] after it. */
    fun <T> withDeadline(deadline: Long, block: () -> T): T {
        val prev = deadlineTl.get()
        deadlineTl.set(if (prev != null) minOf(prev, deadline) else deadline)
        try { return block() } finally { if (prev == null) deadlineTl.remove() else deadlineTl.set(prev) }
    }

    /** Cheap periodic deadline check for the BSP loops. */
    fun tick() {
        val d = deadlineTl.get() ?: return
        val c = ticks.get() ?: IntArray(1).also { ticks.set(it) }
        if (++c[0] and 1023 == 0 && System.currentTimeMillis() > d) throw CsgTimeout()
    }

    fun union(a: List<CsgPolygon>, b: List<CsgPolygon>): List<CsgPolygon> {
        val na = CsgNode(a); val nb = CsgNode(b)
        na.clipTo(nb); nb.clipTo(na); nb.invert(); nb.clipTo(na); nb.invert()
        na.build(nb.allPolygons())
        return na.allPolygons()
    }

    /**
     * Union of many closed solids without building one ever-growing BSP tree: the boundary of the union is,
     * for every solid, the part of its surface that lies outside all the other solids. Each surface is only
     * clipped by the (small) BSP trees of the solids whose boxes it touches, so fragments stay local and
     * the cost grows with the number of touching pairs instead of the total polygon count.
     * Coplanar, same-facing faces are kept once (from the earlier solid), mirroring csg.js' union.
     */
    fun unionAll(solids: List<List<CsgPolygon>>, boxes: List<FloatArray>, eps: Float = 0.01f): List<CsgPolygon> {
        val trees = arrayOfNulls<CsgNode>(solids.size)
        fun tree(j: Int) = trees[j] ?: CsgNode(solids[j]).also { trees[j] = it }
        fun touch(a: FloatArray, b: FloatArray) =
            a[0] <= b[3] + eps && b[0] <= a[3] + eps && a[1] <= b[4] + eps && b[1] <= a[4] + eps && a[2] <= b[5] + eps && b[2] <= a[5] + eps
        val out = ArrayList<CsgPolygon>()
        for (i in solids.indices) {
            val others = solids.indices.filter { it != i && touch(boxes[i], boxes[it]) }
            for (poly in solids[i]) {
                tick()
                var frags = listOf(poly)
                for (j in others) {
                    val next = ArrayList<CsgPolygon>()
                    for (f in frags) {
                        if (!touch(bbox(f), boxes[j])) { next.add(f); continue }
                        val t = tree(j)
                        var c = t.clipPolygons(listOf(f))
                        if (j < i) c = t.clipPolygons(c.map { it.flipped() }).map { it.flipped() }
                        // Untouched by solid j: keep the original piece instead of its BSP fragments.
                        if (c.size > 1 && c.sumOf { area(it) } >= area(f) * (1 - 1e-9)) next.add(f) else next.addAll(c)
                    }
                    frags = next
                    if (frags.isEmpty()) break
                }
                out.addAll(frags)
            }
        }
        return out
    }

    private fun area(p: CsgPolygon): Double {
        val v = p.vertices
        var a = 0.0
        for (k in 1 until v.size - 1) a += (v[k] - v[0]).cross(v[k + 1] - v[0]).length()
        return a / 2
    }

    private fun bbox(p: CsgPolygon): FloatArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var z0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var z1 = -Double.MAX_VALUE
        for (v in p.vertices) { x0 = minOf(x0, v.x); y0 = minOf(y0, v.y); z0 = minOf(z0, v.z); x1 = maxOf(x1, v.x); y1 = maxOf(y1, v.y); z1 = maxOf(z1, v.z) }
        return floatArrayOf(x0.toFloat(), y0.toFloat(), z0.toFloat(), x1.toFloat(), y1.toFloat(), z1.toFloat())
    }

    fun fromMesh(world: Mesh, tag: Int): List<CsgPolygon> {
        val p = world.positions
        val out = ArrayList<CsgPolygon>(world.triangleCount)
        var t = 0
        while (t < p.size) {
            val a = CsgVec(p[t].toDouble(), p[t + 1].toDouble(), p[t + 2].toDouble())
            val b = CsgVec(p[t + 3].toDouble(), p[t + 4].toDouble(), p[t + 5].toDouble())
            val c = CsgVec(p[t + 6].toDouble(), p[t + 7].toDouble(), p[t + 8].toDouble())
            if ((b - a).cross(c - a).length() > 1e-10) out.add(CsgPolygon(listOf(a, b, c), tag))
            t += 9
        }
        return out
    }
}
