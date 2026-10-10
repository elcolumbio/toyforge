package com.kidtoy.geometry

import com.kidtoy.model.Shape
import com.kidtoy.model.Vec3

object Transforms {
    fun model(s: Shape, extraScale: Float = 1f): FloatArray {
        val t = Mat4.translate(s.position.x, s.position.y, s.position.z)
        val r = Mat4.mul(Mat4.rotZ(s.rotation.z), Mat4.mul(Mat4.rotY(s.rotation.y), Mat4.rotX(s.rotation.x)))
        val sc = Mat4.scale(s.scale.x * extraScale, s.scale.y * extraScale, s.scale.z * extraScale)
        return Mat4.mul(t, Mat4.mul(r, sc))
    }

    private val boundsCache = object : LinkedHashMap<Pair<Shape, Int>, FloatArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Shape, Int>, FloatArray>?) = size > 1024
    }

    /** Cached [worldBounds]; shapes are immutable data classes so equal shapes have equal bounds. */
    fun bounds(s: Shape, mesh: Mesh): FloatArray {
        val key = s to System.identityHashCode(mesh)
        synchronized(boundsCache) { boundsCache[key]?.let { return it } }
        val b = worldBounds(s, mesh)
        synchronized(boundsCache) { boundsCache[key] = b }
        return b
    }

    /** Exact world AABB (minX,minY,minZ,maxX,maxY,maxZ) from the transformed mesh vertices. */
    fun worldBounds(s: Shape, mesh: Mesh): FloatArray {
        val m = model(s)
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val out = FloatArray(3)
        val p = mesh.positions
        var i = 0
        while (i < p.size) {
            Mat4.transformPoint(m, p[i], p[i + 1], p[i + 2], out)
            for (a in 0 until 3) {
                if (out[a] < b[a]) b[a] = out[a]
                if (out[a] > b[a + 3]) b[a + 3] = out[a]
            }
            i += 3
        }
        return b
    }

    /** Applies the shape's transform to the mesh, returning world-space positions + normals. */
    fun toWorld(s: Shape, mesh: Mesh): Mesh {
        val m = model(s)
        val nm = Mat4.transpose(Mat4.invert(m) ?: Mat4.identity())
        val p = FloatArray(mesh.positions.size); val n = FloatArray(mesh.normals.size)
        val o = FloatArray(3)
        var i = 0
        while (i < p.size) {
            Mat4.transformPoint(m, mesh.positions[i], mesh.positions[i + 1], mesh.positions[i + 2], o)
            p[i] = o[0]; p[i + 1] = o[1]; p[i + 2] = o[2]
            Mat4.transformDir(nm, mesh.normals[i], mesh.normals[i + 1], mesh.normals[i + 2], o)
            val l = kotlin.math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]).coerceAtLeast(1e-9f)
            n[i] = o[0] / l; n[i + 1] = o[1] / l; n[i + 2] = o[2] / l
            i += 3
        }
        // Mirroring (negative determinant) flips winding; restore outward orientation.
        if (determinant3(m) < 0) {
            var t = 0
            while (t < p.size) {
                for (a in 0 until 3) {
                    val tmp = p[t + 3 + a]; p[t + 3 + a] = p[t + 6 + a]; p[t + 6 + a] = tmp
                    val tn = n[t + 3 + a]; n[t + 3 + a] = n[t + 6 + a]; n[t + 6 + a] = tn
                }
                t += 9
            }
        }
        return Mesh(p, n)
    }

    private fun determinant3(m: FloatArray): Float =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    fun center(b: FloatArray) = Vec3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2)
}
