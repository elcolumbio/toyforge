package com.kidtoy.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import com.kidtoy.geometry.Mesh
import com.kidtoy.model.Shape
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.hypot

/**
 * The 3D canvas. Gestures:
 *  - 1 finger on a part: select it and slide it across the plate
 *  - 1 finger on empty space: orbit;  tap empty space: deselect;  double-tap empty space: reset view
 *  - 2 fingers: pinch to zoom, drag to pan
 */
@SuppressLint("ViewConstructor")
class ToyGLView(context: Context, private val listener: Listener) : GLSurfaceView(context) {
    interface Listener {
        fun shapes(): List<Shape>
        fun meshFor(s: Shape): Mesh
        fun onTap(id: String?)
        fun onDragStart(id: String)
        fun onDrag(id: String, x: Float, z: Float)
        fun onDragEnd(id: String)
    }

    val camera = OrbitCamera()
    val renderer = ToyRenderer(camera)
    private val pulsePending = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        camera.onChange = { requestRender() }
        renderer.wake = { requestRender() }
        renderer.scheduleNext = { delay ->
            when {
                delay == null -> Unit
                delay <= 0L -> requestRender()
                pulsePending.compareAndSet(false, true) -> postDelayed({ pulsePending.set(false); requestRender() }, delay)
            }
        }
    }


    private enum class Mode { PENDING, ORBIT, DRAG, MULTI }
    private var mode = Mode.PENDING
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var hitId: String? = null
    private var planeY = 0f
    private var grabX = 0f; private var grabZ = 0f
    private var startPosX = 0f; private var startPosZ = 0f
    private var lastTapAt = 0L
    private var midX = 0f; private var midY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { camera.zoom(d.scaleFactor); return true }
    })

    private fun rayHitPlane(x: Float, y: Float, py: Float): FloatArray? {
        val (o, d) = camera.ray(x, y, width, height)
        if (kotlin.math.abs(d[1]) < 1e-4f) return null
        val t = (py - o[1]) / d[1]
        if (t < 0) return null
        return floatArrayOf(o[0] + d[0] * t, py, o[2] + d[2] * t)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.PENDING
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                val (o, d) = camera.ray(e.x, e.y, width, height)
                val shapes = listener.shapes()
                val hit = Picking.pick(shapes, listener::meshFor, o, d)
                hitId = hit?.first
                if (hit != null) {
                    planeY = o[1] + d[1] * hit.second
                    grabX = o[0] + d[0] * hit.second; grabZ = o[2] + d[2] * hit.second
                    shapes.firstOrNull { it.id == hit.first }?.let { startPosX = it.position.x; startPosZ = it.position.z }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAG) hitId?.let { listener.onDragEnd(it) }
                mode = Mode.MULTI
                midX = (e.getX(0) + e.getX(1)) / 2; midY = (e.getY(0) + e.getY(1)) / 2
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.MULTI) {
                    if (e.pointerCount >= 2) {
                        val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                        camera.pan(mx - midX, my - midY, height)
                        midX = mx; midY = my
                    }
                } else {
                    if (mode == Mode.PENDING && hypot(e.x - downX, e.y - downY) > slop) {
                        mode = if (hitId != null) Mode.DRAG else Mode.ORBIT
                        if (mode == Mode.DRAG) listener.onDragStart(hitId!!)
                    }
                    when (mode) {
                        Mode.ORBIT -> camera.orbit(-(e.x - lastX) * 0.35f, (e.y - lastY) * 0.25f)
                        Mode.DRAG -> rayHitPlane(e.x, e.y, planeY)?.let { p ->
                            listener.onDrag(hitId!!, startPosX + (p[0] - grabX), startPosZ + (p[2] - grabZ))
                        }
                        else -> {}
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // continue with the remaining finger as an orbit, without a jump
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep)
                if (e.pointerCount - 1 < 2) mode = Mode.ORBIT
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.PENDING -> {
                        val now = System.currentTimeMillis()
                        if (hitId == null && now - lastTapAt < 320) camera.home()
                        lastTapAt = now
                        listener.onTap(hitId)
                    }
                    Mode.DRAG -> hitId?.let { listener.onDragEnd(it) }
                    else -> {}
                }
                mode = Mode.PENDING
            }
            MotionEvent.ACTION_CANCEL -> { if (mode == Mode.DRAG) hitId?.let { listener.onDragEnd(it) }; mode = Mode.PENDING }
        }
        return true
    }

    /** Prefers 4x MSAA for smooth edges, falls back to a plain RGB888 + depth config. */
    private class MultisampleConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val ms = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE,
            )
            val plain = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE,
            )
            for (attrs in listOf(ms, plain)) {
                val n = IntArray(1)
                val configs = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, attrs, configs, 1, n) && n[0] > 0) return configs[0]!!
            }
            throw IllegalStateException("No EGL config")
        }
    }
}
