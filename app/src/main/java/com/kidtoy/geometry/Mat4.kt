package com.kidtoy.geometry

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Minimal column-major 4x4 matrix helpers (pure Kotlin so they are unit-testable). */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    fun translate(x: Float, y: Float, z: Float) = identity().also { it[12] = x; it[13] = y; it[14] = z }
    fun scale(x: Float, y: Float, z: Float) = identity().also { it[0] = x; it[5] = y; it[10] = z }

    fun rotX(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return identity().also { it[5] = c; it[6] = s; it[9] = -s; it[10] = c }
    }
    fun rotY(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return identity().also { it[0] = c; it[2] = -s; it[8] = s; it[10] = c }
    }
    fun rotZ(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return identity().also { it[0] = c; it[1] = s; it[4] = -s; it[5] = c }
    }

    fun perspective(fovyDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovyDeg / 2.0)).toFloat()
        val m = FloatArray(16)
        m[0] = f / aspect; m[5] = f
        m[10] = (far + near) / (near - far); m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return m
    }

    fun lookAt(ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float): FloatArray {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        val fl = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        var sx = fy * uz - fz * uy; var sy = fz * ux - fx * uz; var sz = fx * uy - fy * ux
        val sl = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz); sx /= sl; sy /= sl; sz /= sl
        val ux2 = sy * fz - sz * fy; val uy2 = sz * fx - sx * fz; val uz2 = sx * fy - sy * fx
        val m = identity()
        m[0] = sx; m[4] = sy; m[8] = sz
        m[1] = ux2; m[5] = uy2; m[9] = uz2
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(ux2 * ex + uy2 * ey + uz2 * ez)
        m[14] = (fx * ex + fy * ey + fz * ez)
        return m
    }

    fun invert(m: FloatArray): FloatArray? {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        var det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (kotlin.math.abs(det) < 1e-12f) return null
        det = 1f / det
        for (i in 0 until 16) inv[i] *= det
        return inv
    }

    fun transpose(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 4) for (j in 0 until 4) r[i * 4 + j] = m[j * 4 + i]
        return r
    }

    /** Transforms point (x,y,z,1); returns xyz (with perspective divide). */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        out[0] = (m[0] * x + m[4] * y + m[8] * z + m[12]) / w
        out[1] = (m[1] * x + m[5] * y + m[9] * z + m[13]) / w
        out[2] = (m[2] * x + m[6] * y + m[10] * z + m[14]) / w
        return out
    }

    fun transformDir(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray = FloatArray(3)): FloatArray {
        out[0] = m[0] * x + m[4] * y + m[8] * z
        out[1] = m[1] * x + m[5] * y + m[9] * z
        out[2] = m[2] * x + m[6] * y + m[10] * z
        return out
    }
}
