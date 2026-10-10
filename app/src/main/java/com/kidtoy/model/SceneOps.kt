package com.kidtoy.model

import com.kidtoy.geometry.Mesh
import com.kidtoy.geometry.Solidify
import com.kidtoy.geometry.Transforms
import kotlin.math.roundToInt

/** Pure scene rules (no Android), shared by the editor and the tests. */
object SceneOps {
    const val PLATE_HALF = 100f // 200 x 200 mm build plate
    const val MAX_HEIGHT = 200f
    const val GRID = 5f
    const val MIN_FEATURE = 2f

    fun snap(v: Float, step: Float = GRID) = (v / step).roundToInt() * step

    /** Keeps a shape on the plate: never below y = 0 and fully inside the plate footprint. */
    fun settle(s: Shape, mesh: Mesh): Shape {
        val b = Transforms.bounds(s, mesh)
        var dx = 0f; var dz = 0f
        if (b[0] < -PLATE_HALF) dx = -PLATE_HALF - b[0] else if (b[3] > PLATE_HALF) dx = PLATE_HALF - b[3]
        if (b[2] < -PLATE_HALF) dz = -PLATE_HALF - b[2] else if (b[5] > PLATE_HALF) dz = PLATE_HALF - b[5]
        val dy = if (b[1] < 0f) -b[1] else 0f
        return if (dx == 0f && dy == 0f && dz == 0f) s else s.copy(position = s.position + Vec3(dx, dy, dz))
    }

    /** Moves a group of shapes (e.g. from the AI) so it sits centred on the plate with its lowest point at y = 0. */
    fun placeGroup(shapes: List<Shape>, meshFor: (Shape) -> Mesh, centerX: Float = 0f, centerZ: Float = 0f): List<Shape> {
        if (shapes.isEmpty()) return shapes
        val b = groupBounds(shapes, meshFor)
        val off = Vec3(centerX - (b[0] + b[3]) / 2, -b[1], centerZ - (b[2] + b[5]) / 2)
        return shapes.map { s -> settle(s.copy(position = s.position + off), meshFor(s)) }
    }

    fun groupBounds(shapes: List<Shape>, meshFor: (Shape) -> Mesh): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (s in shapes) {
            val w = Transforms.bounds(s, meshFor(s))
            for (a in 0 until 3) { b[a] = minOf(b[a], w[a]); b[a + 3] = maxOf(b[a + 3], w[a + 3]) }
        }
        return b
    }

    enum class Issue { EMPTY, FLOATING, TINY, TOO_BIG, NOT_ON_PLATE }

    data class Report(val issues: Set<Issue>, val floatingIds: Set<String>, val tinyIds: Set<String>, val size: Vec3) {
        val ok get() = issues.isEmpty()
    }

    /** Printability basics: something touches the plate, nothing floats, nothing is tiny, it fits the printer. */
    fun check(shapes: List<Shape>, meshFor: (Shape) -> Mesh): Report {
        if (shapes.isEmpty()) return Report(setOf(Issue.EMPTY), emptySet(), emptySet(), Vec3())
        val bounds = shapes.map { Transforms.bounds(it, meshFor(it)) }
        val issues = HashSet<Issue>()
        val tiny = HashSet<String>()
        shapes.forEachIndexed { i, s ->
            val b = bounds[i]
            if (minOf(b[3] - b[0], b[4] - b[1], b[5] - b[2]) < MIN_FEATURE) tiny.add(s.id)
        }
        if (tiny.isNotEmpty()) issues.add(Issue.TINY)
        // connectivity from the plate through touching boxes
        val grounded = BooleanArray(shapes.size) { bounds[it][1] <= 0.5f }
        if (grounded.none { it }) issues.add(Issue.NOT_ON_PLATE)
        var changed = true
        while (changed) {
            changed = false
            for (i in shapes.indices) if (!grounded[i]) {
                for (j in shapes.indices) if (grounded[j] && Solidify.overlap(bounds[i], bounds[j], 0.2f)) { grounded[i] = true; changed = true; break }
            }
        }
        val floating = shapes.indices.filter { !grounded[it] }.map { shapes[it].id }.toSet()
        if (floating.isNotEmpty()) issues.add(Issue.FLOATING)
        val g = groupBounds(shapes, meshFor)
        val size = Vec3(g[3] - g[0], g[4] - g[1], g[5] - g[2])
        if (size.x > 2 * PLATE_HALF + 0.1f || size.z > 2 * PLATE_HALF + 0.1f || size.y > MAX_HEIGHT) issues.add(Issue.TOO_BIG)
        return Report(issues, floating, tiny, size)
    }
}
