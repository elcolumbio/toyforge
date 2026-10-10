package com.kidtoy.render

import android.graphics.Bitmap
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.kidtoy.geometry.Mat4
import com.kidtoy.geometry.Mesh
import com.kidtoy.geometry.Primitives
import com.kidtoy.geometry.Transforms
import com.kidtoy.model.SceneOps
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

/** Immutable snapshot of what to draw; swapped atomically from the UI thread. */
data class RenderScene(
    val shapes: List<Shape> = emptyList(),
    val selectedId: String? = null,
    val warnIds: Set<String> = emptySet(),
    val meshes: Map<String, Mesh> = emptyMap(),
)

/** Small OpenGL ES 2.0 renderer: soft gradient sky, build plate with grid, blob shadows, lit parts, selection ring. */
class ToyRenderer(val camera: OrbitCamera) : GLSurfaceView.Renderer {
    @Volatile var scene = RenderScene()
        set(value) {
            val now = SystemClock.uptimeMillis()
            if (initialised) for (s in value.shapes) if (spawn[s.id] == null && field.shapes.none { it.id == s.id }) spawn[s.id] = now
            for (s in value.shapes) spawn.putIfAbsent(s.id, 0L)
            field = value
            initialised = true
            wake()
        }
    private var initialised = false
    private val spawn = java.util.concurrent.ConcurrentHashMap<String, Long>()

    var width = 1; private set
    var height = 1; private set

    @Volatile private var captureCallback: ((Bitmap) -> Unit)? = null
    fun requestCapture(cb: (Bitmap) -> Unit) { captureCallback = cb; wake() }

    /**
     * On-demand rendering: [wake] asks for one frame now; [scheduleNext] is told after each frame how soon the
     * next one is needed (0 = right away while something moves, >0 = gentle pulse of a selection, null = idle).
     * This keeps the GPU (and the battery) quiet when nothing changes.
     */
    @Volatile var wake: () -> Unit = {}
    @Volatile var scheduleNext: (Long?) -> Unit = {}

    private var litProgram = 0
    private var flatProgram = 0
    private var shadowProgram = 0
    private var skyProgram = 0
    private val vbos = HashMap<String, IntArray>() // key -> [vbo, vertexCount]
    private var gridVbo = 0; private var gridCount = 0
    private var quadVbo = 0
    private var lastFrame = 0L
    /** Thin flat ring (outer radius 0.5) used as the selection marker on the plate. */
    private val ringMesh = Primitives.torus(64, 10, rr = 0.47, r = 0.03)

    fun meshFor(s: Shape, sc: RenderScene = scene): Mesh =
        com.kidtoy.geometry.ShapeMeshes.of(s, sc.meshes)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vbos.clear()
        litProgram = program(LIT_VS, LIT_FS)
        flatProgram = program(FLAT_VS, FLAT_FS)
        shadowProgram = program(SHADOW_VS, SHADOW_FS)
        skyProgram = program(SKY_VS, SKY_FS)
        buildGrid()
        quadVbo = upload(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f))
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        val cameraMoving = camera.step(dt)
        val sc = scene

        glClearColor(0.91f, 0.96f, 1f, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawSky()

        val view = camera.view(); val proj = camera.proj(width, height); val vp = Mat4.mul(proj, view)
        val eye = camera.eye()

        // plate slab
        drawLit(vp, eye, "plate", Primitives.get(ShapeType.CUBE),
            Mat4.mul(Mat4.translate(0f, -2f, 0f), Mat4.scale(2 * SceneOps.PLATE_HALF + 8, 4f, 2 * SceneOps.PLATE_HALF + 8)),
            floatArrayOf(1f, 1f, 1f), 0f)
        drawGrid(vp)

        // shadows
        glDisable(GL_CULL_FACE); glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        for (s in sc.shapes) {
            val b = Transforms.bounds(s.copy(scale = s.scale * popScale(s.id, now)), meshFor(s, sc))
            val lift = b[1].coerceAtLeast(0f)
            val strength = (0.32f * (1f - lift / 150f)).coerceIn(0.06f, 0.32f)
            val grow = 1.08f + lift / 200f
            val m = Mat4.mul(Mat4.translate((b[0] + b[3]) / 2, 0.15f, (b[2] + b[5]) / 2),
                Mat4.scale((b[3] - b[0]) / 2 * grow + 1.5f, 1f, (b[5] - b[2]) / 2 * grow + 1.5f))
            drawShadow(vp, m, strength)
        }
        glDepthMask(true); glDisable(GL_BLEND); glEnable(GL_CULL_FACE)

        // parts
        val pulse = 0.5f + 0.5f * sin(now / 220.0).toFloat()
        for (s in sc.shapes) {
            val pop = popScale(s.id, now)
            val model = Transforms.model(s, pop)
            val key = com.kidtoy.geometry.ShapeMeshes.key(s)
            val c = s.color
            val rgb = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
            val hl = when {
                s.id == sc.selectedId -> 0.55f + 0.45f * pulse
                s.id in sc.warnIds -> 0.0f
                else -> 0f
            }
            if (s.type == ShapeType.MESH || determinant(model) < 0) glDisable(GL_CULL_FACE)
            drawLit(vp, eye, key, meshFor(s, sc), model, rgb, hl, warn = s.id in sc.warnIds, pulse = pulse)
            glEnable(GL_CULL_FACE)
        }

        // selection ring on the plate
        sc.shapes.firstOrNull { it.id == sc.selectedId }?.let { s ->
            val b = Transforms.bounds(s, meshFor(s, sc))
            val r = maxOf(b[3] - b[0], b[5] - b[2]) * 0.6f + 6f + 1.5f * pulse
            val m = Mat4.mul(Mat4.translate((b[0] + b[3]) / 2, 0.8f, (b[2] + b[5]) / 2), Mat4.scale(r * 2, r * 2, r * 2))
            drawLit(vp, eye, "ring", ringMesh, m, floatArrayOf(1f, 0.78f + 0.12f * pulse, 0.15f), 0.4f)
        }

        captureCallback?.let { cb -> captureCallback = null; cb(readPixels()) }

        val popping = spawn.values.any { it != 0L }
        scheduleNext(when {
            cameraMoving || popping -> 0L
            sc.selectedId != null || sc.warnIds.isNotEmpty() -> 33L
            else -> null
        })
    }

    private fun popScale(id: String, now: Long): Float {
        val t0 = spawn[id] ?: return 1f
        if (t0 == 0L) return 1f
        val x = ((now - t0) / 380f).coerceIn(0f, 1f)
        if (x >= 1f) { spawn[id] = 0L; return 1f }
        val c1 = 1.9f; val c3 = c1 + 1f
        val xm = x - 1f
        return (1f + c3 * xm * xm * xm + c1 * xm * xm).coerceAtLeast(0.01f)
    }

    private fun determinant(m: FloatArray) =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    private fun vboFor(key: String, mesh: Mesh): IntArray = vbos.getOrPut(key) {
        val inter = FloatArray(mesh.positions.size * 2)
        var i = 0; var j = 0
        while (i < mesh.positions.size) {
            inter[j] = mesh.positions[i]; inter[j + 1] = mesh.positions[i + 1]; inter[j + 2] = mesh.positions[i + 2]
            inter[j + 3] = mesh.normals[i]; inter[j + 4] = mesh.normals[i + 1]; inter[j + 5] = mesh.normals[i + 2]
            i += 3; j += 6
        }
        intArrayOf(upload(inter), mesh.positions.size / 3)
    }

    private fun drawLit(vp: FloatArray, eye: FloatArray, key: String, mesh: Mesh, model: FloatArray, rgb: FloatArray, highlight: Float, warn: Boolean = false, pulse: Float = 0f) {
        val (vbo, count) = vboFor(key, mesh).let { it[0] to it[1] }
        glUseProgram(litProgram)
        val p = litProgram
        glUniformMatrix4fv(glGetUniformLocation(p, "uMVP"), 1, false, Mat4.mul(vp, model), 0)
        glUniformMatrix4fv(glGetUniformLocation(p, "uModel"), 1, false, model, 0)
        glUniformMatrix4fv(glGetUniformLocation(p, "uNormal"), 1, false, Mat4.transpose(Mat4.invert(model) ?: Mat4.identity()), 0)
        glUniform3f(glGetUniformLocation(p, "uColor"), rgb[0], rgb[1], rgb[2])
        glUniform3f(glGetUniformLocation(p, "uEye"), eye[0], eye[1], eye[2])
        glUniform1f(glGetUniformLocation(p, "uHighlight"), highlight)
        glUniform1f(glGetUniformLocation(p, "uWarn"), if (warn) 0.35f + 0.4f * pulse else 0f)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        val aPos = glGetAttribLocation(p, "aPos"); val aNrm = glGetAttribLocation(p, "aNrm")
        glEnableVertexAttribArray(aPos); glVertexAttribPointer(aPos, 3, GL_FLOAT, false, 24, 0)
        glEnableVertexAttribArray(aNrm); glVertexAttribPointer(aNrm, 3, GL_FLOAT, false, 24, 12)
        glDrawArrays(GL_TRIANGLES, 0, count)
        glDisableVertexAttribArray(aPos); glDisableVertexAttribArray(aNrm)
    }

    private fun drawShadow(vp: FloatArray, model: FloatArray, strength: Float) {
        glUseProgram(shadowProgram)
        // quad lies in XZ: remap xy of the unit quad to xz
        val toXZ = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f)
        glUniformMatrix4fv(glGetUniformLocation(shadowProgram, "uMVP"), 1, false, Mat4.mul(vp, Mat4.mul(model, toXZ)), 0)
        glUniform1f(glGetUniformLocation(shadowProgram, "uStrength"), strength)
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo)
        val a = glGetAttribLocation(shadowProgram, "aPos")
        glEnableVertexAttribArray(a); glVertexAttribPointer(a, 2, GL_FLOAT, false, 8, 0)
        glDrawArrays(GL_TRIANGLES, 0, 6)
        glDisableVertexAttribArray(a)
    }

    private fun drawSky() {
        glDisable(GL_DEPTH_TEST); glDepthMask(false)
        glUseProgram(skyProgram)
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo)
        val a = glGetAttribLocation(skyProgram, "aPos")
        glEnableVertexAttribArray(a); glVertexAttribPointer(a, 2, GL_FLOAT, false, 8, 0)
        glDrawArrays(GL_TRIANGLES, 0, 6)
        glDisableVertexAttribArray(a)
        glDepthMask(true); glEnable(GL_DEPTH_TEST)
    }

    private fun buildGrid() {
        val l = ArrayList<Float>()
        val h = SceneOps.PLATE_HALF
        var x = -h
        while (x <= h + 0.01f) {
            l.addAll(listOf(x, 0.05f, -h, x, 0.05f, h)); l.addAll(listOf(-h, 0.05f, x, h, 0.05f, x))
            x += 10f
        }
        gridVbo = upload(l.toFloatArray()); gridCount = l.size / 3
    }

    private fun drawGrid(vp: FloatArray) {
        glUseProgram(flatProgram)
        glUniformMatrix4fv(glGetUniformLocation(flatProgram, "uMVP"), 1, false, vp, 0)
        glUniform4f(glGetUniformLocation(flatProgram, "uColor"), 0.62f, 0.78f, 0.95f, 1f)
        glBindBuffer(GL_ARRAY_BUFFER, gridVbo)
        val a = glGetAttribLocation(flatProgram, "aPos")
        glEnableVertexAttribArray(a); glVertexAttribPointer(a, 3, GL_FLOAT, false, 12, 0)
        glLineWidth(2f)
        glDrawArrays(GL_LINES, 0, gridCount)
        glDisableVertexAttribArray(a)
    }

    private fun readPixels(): Bitmap {
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(bmp, 0, 0, width, height, m, false)
    }

    private fun upload(data: FloatArray): Int {
        val ids = IntArray(1); glGenBuffers(1, ids, 0)
        val fb: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, fb, GL_STATIC_DRAW)
        return ids[0]
    }

    /** Forget a GPU mesh (e.g. an imported model that was removed). Must run on the GL thread. */
    fun evict(key: String) { vbos.remove(key)?.let { glDeleteBuffers(1, intArrayOf(it[0]), 0) } }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = glCreateShader(type); glShaderSource(s, src); glCompileShader(s)
            val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("shader: " + glGetShaderInfoLog(s))
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, sh(GL_VERTEX_SHADER, vs)); glAttachShader(p, sh(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        return p
    }

    companion object {
        const val LIT_VS = """
uniform mat4 uMVP; uniform mat4 uModel; uniform mat4 uNormal;
attribute vec3 aPos; attribute vec3 aNrm;
varying vec3 vN; varying vec3 vW;
void main() {
  vW = (uModel * vec4(aPos, 1.0)).xyz;
  vN = (uNormal * vec4(aNrm, 0.0)).xyz;
  gl_Position = uMVP * vec4(aPos, 1.0);
}"""
        const val LIT_FS = """
precision mediump float;
uniform vec3 uColor; uniform vec3 uEye; uniform float uHighlight; uniform float uWarn;
varying vec3 vN; varying vec3 vW;
void main() {
  vec3 n = normalize(vN);
  vec3 v = normalize(uEye - vW);
  if (dot(n, v) < 0.0) n = -n;
  vec3 l1 = normalize(vec3(0.45, 1.0, 0.65));
  vec3 l2 = normalize(vec3(-0.7, 0.35, -0.5));
  float d = max(dot(n, l1), 0.0) * 0.72 + max(dot(n, l2), 0.0) * 0.22;
  float hemi = 0.5 + 0.5 * n.y;
  vec3 amb = mix(vec3(0.55, 0.50, 0.58), vec3(0.80, 0.88, 1.0), hemi) * 0.48;
  vec3 h = normalize(l1 + v);
  float spec = pow(max(dot(n, h), 0.0), 48.0) * 0.32;
  float rim = pow(1.0 - max(dot(n, v), 0.0), 2.2);
  vec3 c = uColor * (amb + d * 0.78) + vec3(spec) + rim * 0.10;
  c = mix(c, vec3(1.0, 0.93, 0.35), rim * uHighlight) + uHighlight * 0.05;
  c = mix(c, vec3(1.0, 0.25, 0.2), uWarn * (0.35 + 0.65 * rim));
  gl_FragColor = vec4(c, 1.0);
}"""
        const val FLAT_VS = """
uniform mat4 uMVP; attribute vec3 aPos;
void main() { gl_Position = uMVP * vec4(aPos, 1.0); }"""
        const val FLAT_FS = """
precision mediump float; uniform vec4 uColor;
void main() { gl_FragColor = uColor; }"""
        const val SHADOW_VS = """
uniform mat4 uMVP; attribute vec2 aPos; varying vec2 vUv;
void main() { vUv = aPos; gl_Position = uMVP * vec4(aPos, 0.0, 1.0); }"""
        const val SHADOW_FS = """
precision mediump float; uniform float uStrength; varying vec2 vUv;
void main() { float r = length(vUv); float a = (1.0 - smoothstep(0.35, 1.0, r)) * uStrength; gl_FragColor = vec4(0.15, 0.2, 0.45, a); }"""
        const val SKY_VS = """
attribute vec2 aPos; varying float vY;
void main() { vY = aPos.y * 0.5 + 0.5; gl_Position = vec4(aPos, 0.999, 1.0); }"""
        const val SKY_FS = """
precision mediump float; varying float vY;
void main() {
  vec3 top = vec3(0.60, 0.82, 1.0); vec3 bottom = vec3(1.0, 0.95, 0.88);
  gl_FragColor = vec4(mix(bottom, top, smoothstep(0.0, 1.0, vY)), 1.0);
}"""
    }
}
