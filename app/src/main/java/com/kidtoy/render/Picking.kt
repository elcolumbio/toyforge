package com.kidtoy.render

import com.kidtoy.geometry.Mat4
import com.kidtoy.geometry.Mesh
import com.kidtoy.geometry.Transforms
import com.kidtoy.model.Shape

object Picking {
    /** Returns the id of the nearest shape hit by the ray and the hit distance. */
    fun pick(shapes: List<Shape>, meshFor: (Shape) -> Mesh, o: FloatArray, d: FloatArray): Pair<String, Float>? {
        var best: Pair<String, Float>? = null
        val lo = FloatArray(3); val ld = FloatArray(3)
        for (s in shapes) {
            val inv = Mat4.invert(Transforms.model(s)) ?: continue
            Mat4.transformPoint(inv, o[0], o[1], o[2], lo)
            Mat4.transformDir(inv, d[0], d[1], d[2], ld)
            if (!hitsBox(lo, ld)) continue
            val t = nearestTriangle(meshFor(s).positions, lo, ld) ?: continue
            if (best == null || t < best.second) best = s.id to t
        }
        return best
    }

    private fun hitsBox(o: FloatArray, d: FloatArray): Boolean {
        var tmin = -Float.MAX_VALUE; var tmax = Float.MAX_VALUE
        for (a in 0 until 3) {
            if (kotlin.math.abs(d[a]) < 1e-9f) { if (o[a] < -0.51f || o[a] > 0.51f) return false; continue }
            var t1 = (-0.51f - o[a]) / d[a]; var t2 = (0.51f - o[a]) / d[a]
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tmin = maxOf(tmin, t1); tmax = minOf(tmax, t2)
            if (tmin > tmax) return false
        }
        return tmax > 0
    }

    /** Möller–Trumbore over a triangle soup; returns the smallest positive ray parameter. */
    fun nearestTriangle(p: FloatArray, o: FloatArray, d: FloatArray): Float? {
        var best = Float.MAX_VALUE
        var i = 0
        while (i < p.size) {
            val e1x = p[i + 3] - p[i]; val e1y = p[i + 4] - p[i + 1]; val e1z = p[i + 5] - p[i + 2]
            val e2x = p[i + 6] - p[i]; val e2y = p[i + 7] - p[i + 1]; val e2z = p[i + 8] - p[i + 2]
            val hx = d[1] * e2z - d[2] * e2y; val hy = d[2] * e2x - d[0] * e2z; val hz = d[0] * e2y - d[1] * e2x
            val a = e1x * hx + e1y * hy + e1z * hz
            if (kotlin.math.abs(a) > 1e-12f) {
                val f = 1f / a
                val sx = o[0] - p[i]; val sy = o[1] - p[i + 1]; val sz = o[2] - p[i + 2]
                val u = f * (sx * hx + sy * hy + sz * hz)
                if (u in 0f..1f) {
                    val qx = sy * e1z - sz * e1y; val qy = sz * e1x - sx * e1z; val qz = sx * e1y - sy * e1x
                    val v = f * (d[0] * qx + d[1] * qy + d[2] * qz)
                    if (v >= 0f && u + v <= 1f) {
                        val t = f * (e2x * qx + e2y * qy + e2z * qz)
                        if (t > 1e-6f && t < best) best = t
                    }
                }
            }
            i += 9
        }
        return if (best == Float.MAX_VALUE) null else best
    }
}
