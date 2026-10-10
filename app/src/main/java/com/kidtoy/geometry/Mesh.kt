package com.kidtoy.geometry

import kotlin.math.sqrt

/**
 * Non-indexed triangle soup: 9 floats per triangle in [positions] and per-vertex normals in [normals].
 * Triangles are counter-clockwise when seen from outside.
 */
class Mesh(val positions: FloatArray, val normals: FloatArray) {
    val triangleCount: Int get() = positions.size / 9

    fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var i = 0
        while (i < positions.size) {
            for (a in 0 until 3) {
                val v = positions[i + a]
                if (v < b[a]) b[a] = v
                if (v > b[a + 3]) b[a + 3] = v
            }
            i += 3
        }
        return b
    }

    companion object {
        /** Builds a mesh with flat (face) normals from a position-only triangle soup. */
        fun flat(positions: FloatArray): Mesh {
            val n = FloatArray(positions.size)
            var t = 0
            while (t < positions.size) {
                val fn = faceNormal(positions, t)
                for (v in 0 until 3) { n[t + v * 3] = fn[0]; n[t + v * 3 + 1] = fn[1]; n[t + v * 3 + 2] = fn[2] }
                t += 9
            }
            return Mesh(positions, n)
        }

        fun faceNormal(p: FloatArray, t: Int): FloatArray {
            val ux = p[t + 3] - p[t]; val uy = p[t + 4] - p[t + 1]; val uz = p[t + 5] - p[t + 2]
            val vx = p[t + 6] - p[t]; val vy = p[t + 7] - p[t + 1]; val vz = p[t + 8] - p[t + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-12f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 1f; nz = 0f }
            return floatArrayOf(nx, ny, nz)
        }
    }
}

/** Accumulates triangles with explicit per-vertex normals. */
class MeshBuilder {
    private val p = ArrayList<Float>()
    private val n = ArrayList<Float>()

    fun tri(a: FloatArray, b: FloatArray, c: FloatArray, na: FloatArray, nb: FloatArray, nc: FloatArray) {
        for (v in a) p.add(v); for (v in b) p.add(v); for (v in c) p.add(v)
        for (v in na) n.add(v); for (v in nb) n.add(v); for (v in nc) n.add(v)
    }

    fun flatTri(a: FloatArray, b: FloatArray, c: FloatArray) {
        val fn = Mesh.faceNormal(floatArrayOf(*a, *b, *c), 0)
        tri(a, b, c, fn, fn, fn)
    }

    fun flatQuad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) { flatTri(a, b, c); flatTri(a, c, d) }

    fun build() = Mesh(p.toFloatArray(), n.toFloatArray())
}
