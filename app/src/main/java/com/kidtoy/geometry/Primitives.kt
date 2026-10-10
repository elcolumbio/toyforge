package com.kidtoy.geometry

import com.kidtoy.model.ShapeType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Closed (watertight) unit primitives that fit the box [-0.5, 0.5]^3, Y up.
 * Every mesh is a closed 2-manifold (seams share exact vertex positions), which the tests verify.
 */
object Primitives {
    private val cache = HashMap<ShapeType, Mesh>()

    @Synchronized
    fun get(type: ShapeType): Mesh = cache.getOrPut(type) {
        when (type) {
            ShapeType.CUBE -> cube()
            ShapeType.SPHERE -> sphere(32, 20)
            ShapeType.CYLINDER -> cylinder(40)
            ShapeType.CONE -> cone(40)
            ShapeType.TORUS -> torus(40, 20)
            ShapeType.STAR -> extrude(starOutline())
            ShapeType.HEART -> extrude(heartOutline())
            ShapeType.PYRAMID -> pyramid()
            ShapeType.MESH, ShapeType.TEXT -> cube()
        }
    }

    private fun v(x: Double, y: Double, z: Double) = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    fun cube(): Mesh {
        val b = MeshBuilder()
        val h = 0.5f
        val p = arrayOf(
            v(-h, -h, -h), v(h, -h, -h), v(h, h, -h), v(-h, h, -h),
            v(-h, -h, h), v(h, -h, h), v(h, h, h), v(-h, h, h),
        )
        b.flatQuad(p[4], p[5], p[6], p[7]) // +z
        b.flatQuad(p[1], p[0], p[3], p[2]) // -z
        b.flatQuad(p[5], p[1], p[2], p[6]) // +x
        b.flatQuad(p[0], p[4], p[7], p[3]) // -x
        b.flatQuad(p[7], p[6], p[2], p[3]) // +y
        b.flatQuad(p[0], p[1], p[5], p[4]) // -y
        return b.build()
    }

    fun pyramid(): Mesh {
        val b = MeshBuilder()
        val h = 0.5f
        val a = v(-h, -h, h); val c = v(h, -h, h); val d = v(h, -h, -h); val e = v(-h, -h, -h)
        val top = v(0f, h, 0f)
        b.flatTri(a, c, top); b.flatTri(c, d, top); b.flatTri(d, e, top); b.flatTri(e, a, top)
        b.flatQuad(e, d, c, a)
        return b.build()
    }

    fun sphere(seg: Int, rings: Int): Mesh {
        val b = MeshBuilder()
        fun pt(i: Int, j: Int): FloatArray {
            if (j == 0) return v(0f, 0.5f, 0f)
            if (j == rings) return v(0f, -0.5f, 0f)
            val th = PI * j / rings; val ph = 2 * PI * (i % seg) / seg
            return v(0.5 * sin(th) * cos(ph), 0.5 * cos(th), -0.5 * sin(th) * sin(ph))
        }
        fun nrm(p: FloatArray) = floatArrayOf(p[0] * 2, p[1] * 2, p[2] * 2)
        for (j in 0 until rings) for (i in 0 until seg) {
            val a = pt(i, j); val bb = pt(i, j + 1); val c = pt(i + 1, j + 1); val d = pt(i + 1, j)
            if (j != 0) b.tri(a, bb, d, nrm(a), nrm(bb), nrm(d))
            if (j != rings - 1) b.tri(bb, c, d, nrm(bb), nrm(c), nrm(d))
        }
        return b.build()
    }

    private fun ring(seg: Int, i: Int, r: Double, y: Double): FloatArray {
        val a = 2 * PI * (i % seg) / seg
        return v(r * cos(a), y, -r * sin(a))
    }

    fun cylinder(seg: Int): Mesh {
        val b = MeshBuilder()
        val top = v(0f, 0.5f, 0f); val bot = v(0f, -0.5f, 0f)
        val up = v(0f, 1f, 0f); val down = v(0f, -1f, 0f)
        for (i in 0 until seg) {
            val b0 = ring(seg, i, 0.5, -0.5); val b1 = ring(seg, i + 1, 0.5, -0.5)
            val t0 = ring(seg, i, 0.5, 0.5); val t1 = ring(seg, i + 1, 0.5, 0.5)
            val n0 = v(b0[0] * 2, 0f, b0[2] * 2); val n1 = v(b1[0] * 2, 0f, b1[2] * 2)
            b.tri(b0, b1, t1, n0, n1, n1); b.tri(b0, t1, t0, n0, n1, n0)
            b.tri(top, t0, t1, up, up, up)
            b.tri(bot, b1, b0, down, down, down)
        }
        return b.build()
    }

    fun cone(seg: Int): Mesh {
        val b = MeshBuilder()
        val apex = v(0f, 0.5f, 0f); val bot = v(0f, -0.5f, 0f); val down = v(0f, -1f, 0f)
        val k = (0.5 / 1.0).toFloat() // slope factor for normals
        for (i in 0 until seg) {
            val b0 = ring(seg, i, 0.5, -0.5); val b1 = ring(seg, i + 1, 0.5, -0.5)
            fun n(p: FloatArray): FloatArray {
                val x = p[0] * 2; val z = p[2] * 2; val y = k
                val l = sqrt(x * x + y * y + z * z); return v(x / l, y / l, z / l)
            }
            val mid = ring(seg * 2, i * 2 + 1, 0.5, -0.5)
            b.tri(b0, b1, apex, n(b0), n(b1), n(mid))
            b.tri(bot, b1, b0, down, down, down)
        }
        return b.build()
    }

    /** Torus lying flat on the plate: major radius 0.35, tube radius 0.15 (so it is 1 wide, 0.3 tall). */
    fun torus(seg: Int, tube: Int, rr: Double = 0.35, r: Double = 0.15): Mesh {
        val b = MeshBuilder()
        fun pt(i: Int, j: Int): Pair<FloatArray, FloatArray> {
            val u = 2 * PI * (i % seg) / seg; val w = 2 * PI * (j % tube) / tube
            val cx = cos(u); val cz = -sin(u)
            val p = v((rr + r * cos(w)) * cx, r * sin(w), (rr + r * cos(w)) * cz)
            val n = v(cos(w) * cx, sin(w), cos(w) * cz)
            return p to n
        }
        for (i in 0 until seg) for (j in 0 until tube) {
            val (a, na) = pt(i, j); val (bb, nb) = pt(i + 1, j); val (c, nc) = pt(i + 1, j + 1); val (d, nd) = pt(i, j + 1)
            b.tri(a, bb, c, na, nb, nc); b.tri(a, c, d, na, nc, nd)
        }
        return b.build()
    }

    /** Star-shaped 2D outline (x, y) in [-0.5,0.5], counter-clockwise. Fan-triangulated from the origin. */
    fun starOutline(points: Int = 5): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) 0.5 else 0.21
            val a = PI / 2 + PI * i / points
            out.add(r * cos(a) to r * sin(a) - 0.025)
        }
        return out
    }

    fun heartOutline(n: Int = 64): List<Pair<Double, Double>> {
        val raw = (0 until n).map { k ->
            val t = 2 * PI * k / n
            val x = 16 * sin(t) * sin(t) * sin(t)
            val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
            x to y
        }.reversed() // make counter-clockwise
        val minX = raw.minOf { it.first }; val maxX = raw.maxOf { it.first }
        val minY = raw.minOf { it.second }; val maxY = raw.maxOf { it.second }
        val s = 1.0 / maxOf(maxX - minX, maxY - minY)
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        return raw.map { (it.first - cx) * s to (it.second - cy) * s }
    }

    /** Extrudes a star-shaped (w.r.t. origin) CCW outline along Z from -0.5 to 0.5. */
    fun extrude(outline: List<Pair<Double, Double>>): Mesh {
        val b = MeshBuilder()
        val n = outline.size
        val cf = v(0f, 0f, 0.5f); val cb = v(0f, 0f, -0.5f)
        for (i in 0 until n) {
            val (x0, y0) = outline[i]; val (x1, y1) = outline[(i + 1) % n]
            val f0 = v(x0, y0, 0.5); val f1 = v(x1, y1, 0.5)
            val k0 = v(x0, y0, -0.5); val k1 = v(x1, y1, -0.5)
            b.flatTri(cf, f0, f1)
            b.flatTri(cb, k1, k0)
            b.flatQuad(k0, k1, f1, f0)
        }
        return b.build()
    }
}
