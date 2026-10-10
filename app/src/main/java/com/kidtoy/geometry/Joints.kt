package com.kidtoy.geometry

import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * "Stronger joints": how firmly does each part hold on to its neighbours?
 *
 * For every touching pair the overlap region is sampled on a small grid with exact inside tests of the unit
 * primitives (in each part's local frame, so rotated / squashed parts are handled). The joint strength is the
 * *neck*: the largest cross-section of the overlap (over the x/y/z axes and the centre-to-centre direction),
 * i.e. the area of plastic that holds the part on. A part that sticks out needs a neck of at least
 * [MIN_NECK_MM2] (≈ 6 mm round) and more for bigger parts ([NECK_PER_SIZE] × volume^(2/3)).
 * [strengthen] sinks weak parts a little further into their best neighbour until the neck is big enough.
 */
object Joints {
    class Joint(val part: Int, val other: Int, val overlapMm3: Double, val neckMm2: Double, val needMm2: Double, val dir: Vec3,
                val group: List<Int> = listOf(part)) {
        val weak get() = neckMm2 < needMm2
    }

    const val MIN_NECK_MM2 = 28.0
    const val NECK_PER_SIZE = 0.25
    /** Parts smaller than this are decorations (eye glints); they are embedded, not hanging. */
    const val DECORATION_MM3 = 150.0
    /** Groups heavier than this share of the toy are its main mass, not a limb. */
    const val LIMB_SHARE = 0.25
    private const val N = 18

    private class Solid(val s: Shape, val inv: FloatArray, val box: FloatArray, val volume: Double)

    private fun unitVolume(t: ShapeType) = when (t) {
        ShapeType.CUBE -> 1.0; ShapeType.SPHERE -> PI / 6; ShapeType.CYLINDER -> PI / 4; ShapeType.CONE -> PI / 12
        else -> 0.5
    }

    /** Inside test in the unit primitive's frame; null when the type has no exact test (handled by its box). */
    private fun insideUnit(t: ShapeType, x: Float, y: Float, z: Float): Boolean = when (t) {
        ShapeType.SPHERE -> x * x + y * y + z * z <= 0.25f
        ShapeType.CYLINDER -> abs(y) <= 0.5f && x * x + z * z <= 0.25f
        ShapeType.CONE -> abs(y) <= 0.5f && sqrt(x * x + z * z) <= 0.5f * (0.5f - y)
        else -> abs(x) <= 0.5f && abs(y) <= 0.5f && abs(z) <= 0.5f // cube, and a box for the rest
    }

    private fun solids(shapes: List<Shape>, meshFor: (Shape) -> Mesh) = shapes.map { s ->
        Solid(s, Mat4.invert(Transforms.model(s)) ?: Mat4.identity(), Transforms.bounds(s, meshFor(s)),
            unitVolume(s.type) * abs(s.scale.x * s.scale.y * s.scale.z))
    }

    private fun inside(a: Solid, x: Float, y: Float, z: Float, tmp: FloatArray): Boolean {
        Mat4.transformPoint(a.inv, x, y, z, tmp)
        return insideUnit(a.s.type, tmp[0], tmp[1], tmp[2])
    }

    /** Overlap volume and neck (largest cross-section) of two parts. */
    private fun measure(a: Solid, b: Solid): Triple<Double, Double, Vec3>? {
        val lo = FloatArray(3) { maxOf(a.box[it], b.box[it]) }; val hi = FloatArray(3) { minOf(a.box[it + 3], b.box[it + 3]) }
        if ((0 until 3).any { hi[it] <= lo[it] }) return null
        val c = FloatArray(3) { (hi[it] - lo[it]) / N }
        val ca = Transforms.center(a.box); val cb = Transforms.center(b.box)
        var d = cb - ca; val dl = sqrt(d.x * d.x + d.y * d.y + d.z * d.z); d = if (dl > 1e-6f) d * (1f / dl) else Vec3(0f, 1f, 0f)
        val h = abs(d.x) * c[0] + abs(d.y) * c[1] + abs(d.z) * c[2]
        val cellVol = c[0].toDouble() * c[1] * c[2]
        val axisCount = Array(3) { IntArray(N) }
        val dirCount = HashMap<Int, Int>()
        var count = 0
        val tmp = FloatArray(3)
        for (i in 0 until N) for (j in 0 until N) for (k in 0 until N) {
            val x = lo[0] + (i + 0.5f) * c[0]; val y = lo[1] + (j + 0.5f) * c[1]; val z = lo[2] + (k + 0.5f) * c[2]
            if (!inside(a, x, y, z, tmp) || !inside(b, x, y, z, tmp)) continue
            count++
            axisCount[0][i]++; axisCount[1][j]++; axisCount[2][k]++
            val t = Math.floorDiv(((x * d.x + y * d.y + z * d.z) / h).toInt(), 1)
            dirCount.merge(t, 1, Int::plus)
        }
        if (count == 0) return Triple(0.0, 0.0, d)
        val neckAxis = (0 until 3).maxOf { ax -> axisCount[ax].max() * cellVol / c[ax] }
        val neckDir = (dirCount.values.maxOrNull() ?: 0) * cellVol / h
        return Triple(count * cellVol, maxOf(neckAxis, neckDir), d)
    }

    /**
     * Joint tree: starting from the biggest part (usually the base plate or body), every other part hangs on the
     * neighbour that gives it the widest neck (maximum-bottleneck spanning tree). Each joint has to carry the
     * whole group hanging on it (an ear together with its pink inside), so the needed neck grows with that group.
     */
    fun analyse(shapes: List<Shape>, meshFor: (Shape) -> Mesh): List<Joint> {
        if (shapes.size < 2) return emptyList()
        val sol = solids(shapes, meshFor)
        val n = sol.size
        val skip = BooleanArray(n) { sol[it].s.type == ShapeType.MESH }
        val neck = Array(n) { DoubleArray(n) }; val over = Array(n) { DoubleArray(n) }; val dirs = arrayOfNulls<Vec3>(n * n)
        for (i in 0 until n) for (j in i + 1 until n) {
            if (skip[i] || skip[j]) continue
            val m = measure(sol[i], sol[j]) ?: continue
            neck[i][j] = m.second; neck[j][i] = m.second; over[i][j] = m.first; over[j][i] = m.first
            dirs[i * n + j] = m.third; dirs[j * n + i] = m.third * -1f
        }
        val root = (0 until n).filter { !skip[it] }.maxByOrNull { sol[it].volume } ?: return emptyList()
        val parent = IntArray(n) { -1 }; val inTree = BooleanArray(n); val best = DoubleArray(n) { -1.0 }
        inTree[root] = true
        for (j in 0 until n) if (neck[root][j] > 0) { best[j] = neck[root][j]; parent[j] = root }
        while (true) {
            val k = (0 until n).filter { !inTree[it] && best[it] > 0 }.maxByOrNull { best[it] } ?: break
            inTree[k] = true
            for (j in 0 until n) if (!inTree[j] && neck[k][j] > best[j]) { best[j] = neck[k][j]; parent[j] = k }
        }
        fun subtree(i: Int): List<Int> = listOf(i) + (0 until n).filter { parent[it] == i && inTree[it] }.flatMap { subtree(it) }
        val total = (0 until n).filter { !skip[it] }.sumOf { sol[it].volume } - sol[root].volume
        val out = ArrayList<Joint>()
        for (i in 0 until n) {
            if (!inTree[i] || i == root) continue
            val group = subtree(i)
            val groupVol = group.sumOf { sol[it].volume }
            // only things that stick out can snap off: decorations and the main mass (body on its legs) are not limbs
            if (groupVol < DECORATION_MM3 || groupVol > LIMB_SHARE * total || sol[i].s.type == ShapeType.TEXT) continue
            val p = parent[i]
            val need = maxOf(MIN_NECK_MM2, NECK_PER_SIZE * minOf(groupVol, sol[p].volume).pow(2.0 / 3.0))
            // direction from the hanging part towards the part holding it
            out.add(Joint(i, p, over[i][p], neck[i][p], need, dirs[i * n + p]!!, group))
        }
        return out
    }

    fun weak(shapes: List<Shape>, meshFor: (Shape) -> Mesh) = analyse(shapes, meshFor).filter { it.weak && it.neckMm2 > 0 }

    /**
     * Sinks each weakly attached part towards its neighbour in 0.5 mm steps (at most 20% of its own size)
     * until its neck is strong enough. Returns the new scene (same ids) and how many parts were moved.
     */
    fun strengthen(shapes: List<Shape>, meshFor: (Shape) -> Mesh): Pair<List<Shape>, Int> {
        var scene = shapes
        var moved = 0
        // weakest first; each fix moves the whole hanging group so insides / eyes travel along
        for (j in weak(shapes, meshFor).sortedBy { it.neckMm2 / it.needMm2 }) {
            val part = scene[j.part]
            val b = Transforms.bounds(part, meshFor(part))
            val size = maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2])
            val maxShift = (size * 0.15f).coerceIn(1f, 8f)
            var shift = 0.5f
            var done: List<Shape>? = null
            while (shift <= maxShift + 1e-3f) {
                val off = j.dir * shift
                val cand = scene.mapIndexed { k, s -> if (k in j.group) s.copy(position = s.position + off) else s }
                val sol = solids(listOf(cand[j.part], cand[j.other]), meshFor)
                done = cand
                val m = measure(sol[0], sol[1])
                if (m != null && m.second >= j.needMm2) break
                shift += 0.5f
            }
            if (done != null) { scene = done; moved++ }
        }
        return scene to moved
    }
}
