package com.kidtoy.render

import com.kidtoy.geometry.Mat4
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Orbit camera with smoothing: gestures set the goal values, every frame eases towards them. */
class OrbitCamera {
    // goals (written by the UI thread)
    @Volatile var goalYaw = 35f
    @Volatile var goalPitch = 28f
    @Volatile var goalDist = 330f
    @Volatile var goalTx = 0f
    @Volatile var goalTy = 25f
    @Volatile var goalTz = 0f

    // current values (smoothed)
    @Volatile var yaw = goalYaw; @Volatile var pitch = goalPitch; @Volatile var dist = goalDist
    @Volatile var tx = goalTx; @Volatile var ty = goalTy; @Volatile var tz = goalTz

    val fov = 40f

    /** Called whenever a goal changes so an on-demand renderer can wake up. */
    @Volatile var onChange: () -> Unit = {}

    fun orbit(dYaw: Float, dPitch: Float) {
        goalYaw += dYaw
        goalPitch = (goalPitch + dPitch).coerceIn(5f, 85f)
        onChange()
    }

    fun zoom(factor: Float) { goalDist = (goalDist / factor).coerceIn(70f, 900f); onChange() }

    fun pan(dxPixels: Float, dyPixels: Float, viewHeight: Int) {
        val worldPerPixel = 2f * dist * kotlin.math.tan(Math.toRadians(fov / 2.0)).toFloat() / viewHeight.coerceAtLeast(1)
        val yr = Math.toRadians(yaw.toDouble())
        val rx = cos(yr).toFloat(); val rz = -sin(yr).toFloat() // camera right on the ground
        val fx = -sin(yr).toFloat(); val fz = -cos(yr).toFloat() // camera forward on the ground
        goalTx = (goalTx - rx * dxPixels * worldPerPixel + fx * dyPixels * worldPerPixel).coerceIn(-150f, 150f)
        goalTz = (goalTz - rz * dxPixels * worldPerPixel + fz * dyPixels * worldPerPixel).coerceIn(-150f, 150f)
        onChange()
    }

    fun home() { goalYaw = 35f; goalPitch = 28f; goalDist = 330f; goalTx = 0f; goalTy = 25f; goalTz = 0f; onChange() }

    fun frame(bounds: FloatArray) {
        val size = maxOf(bounds[3] - bounds[0], bounds[4] - bounds[1], bounds[5] - bounds[2], 40f)
        goalTx = (bounds[0] + bounds[3]) / 2; goalTz = (bounds[2] + bounds[5]) / 2; goalTy = (bounds[1] + bounds[4]) / 2
        goalDist = (size * 2.6f).coerceIn(140f, 700f)
        onChange()
    }

    /** Eases current values toward goals; returns true while still moving. */
    fun step(dtSec: Float): Boolean {
        val k = 1f - exp(-dtSec * 14f)
        fun e(c: Float, g: Float) = c + (g - c) * k
        yaw = e(yaw, goalYaw); pitch = e(pitch, goalPitch); dist = e(dist, goalDist)
        tx = e(tx, goalTx); ty = e(ty, goalTy); tz = e(tz, goalTz)
        return kotlin.math.abs(yaw - goalYaw) + kotlin.math.abs(pitch - goalPitch) + kotlin.math.abs(dist - goalDist) +
            kotlin.math.abs(tx - goalTx) + kotlin.math.abs(ty - goalTy) + kotlin.math.abs(tz - goalTz) > 0.01f
    }

    fun eye(): FloatArray {
        val yr = Math.toRadians(yaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
        return floatArrayOf(
            tx + (dist * cos(pr) * sin(yr)).toFloat(),
            ty + (dist * sin(pr)).toFloat(),
            tz + (dist * cos(pr) * cos(yr)).toFloat(),
        )
    }

    fun view(): FloatArray { val e = eye(); return Mat4.lookAt(e[0], e[1], e[2], tx, ty, tz, 0f, 1f, 0f) }
    fun proj(w: Int, h: Int) = Mat4.perspective(fov, w.toFloat() / h.coerceAtLeast(1), 5f, 4000f)

    /** World-space ray (origin, direction) through a pixel. */
    fun ray(px: Float, py: Float, w: Int, h: Int): Pair<FloatArray, FloatArray> {
        val inv = Mat4.invert(Mat4.mul(proj(w, h), view())) ?: Mat4.identity()
        val nx = 2f * px / w - 1f; val ny = 1f - 2f * py / h
        val a = Mat4.transformPoint(inv, nx, ny, -1f)
        val b = Mat4.transformPoint(inv, nx, ny, 1f)
        val d = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val l = kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        return a to floatArrayOf(d[0] / l, d[1] / l, d[2] / l)
    }
}
