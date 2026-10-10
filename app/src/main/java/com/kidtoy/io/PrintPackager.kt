package com.kidtoy.io

import com.kidtoy.geometry.Mesh
import com.kidtoy.geometry.SolidMesh
import com.kidtoy.geometry.Solidify
import com.kidtoy.model.Palette
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.model.Vec3
import com.kidtoy.geometry.Transforms

/** Everything the print flow needs: the fused solid (STL), per-colour parts (Bambu 3MF) and the print recipe. */
class PrintPackage(val solid: Solidify.Result, val options: Map<PrintMode, PrintOption>, val plan: PrintPlan, val refined: Boolean = true) {
    val zUpSolid: SolidMesh get() = toZUp(solid.mesh)
    /** Colourful (AMS) parts, one per kept colour. */
    val parts: List<BambuProjectExporter.Part> get() = options.getValue(PrintMode.COLOURFUL).parts

    companion object {
        fun toZUp(m: SolidMesh): SolidMesh {
            val v = FloatArray(m.vertices.size)
            for (i in 0 until m.vertexCount) { v[i * 3] = m.vertices[i * 3]; v[i * 3 + 1] = -m.vertices[i * 3 + 2]; v[i * 3 + 2] = m.vertices[i * 3 + 1] }
            return SolidMesh(v, m.triangles, m.triangleColors)
        }
    }
}

/** One way of printing the toy: the Bambu parts, the estimate and which colours were merged. */
class PrintOption(val mode: PrintMode, val parts: List<BambuProjectExporter.Part>, val estimate: PrintEstimate, val merges: List<Pair<Int, Int>>)

object PrintPackager {
    private val colourNames = listOf("red", "orange", "yellow", "green", "sky blue", "blue", "purple", "pink", "brown", "white", "dark grey")

    fun colourName(c: Int): String = Palette.colors.indexOf(c).let { if (it >= 0) colourNames[it] else String.format("#%06X", c and 0xFFFFFF) }

    /**
     * Instant version (no CSG, ~100 ms on a tablet): every part as its own closed shell, per-colour Bambu parts and a
     * recipe estimated from the shells, ignoring downward faces buried inside other parts.
     * The STL from this package is multi-body (overlapping closed shells), which slicers merge.
     */
    fun quick(shapes: List<Shape>, meshFor: (Shape) -> Mesh): PrintPackage {
        // every part welded once; the whole-toy shells and the per-colour parts share them
        val each = shapes.map { Solidify.run(listOf(it), meshFor, fuse = false).mesh }
        val shells = Solidify.Result(Solidify.concat(each), 0, shapes.count { it.type == ShapeType.MESH })
        val plan = PrintAdvisor.analyse(shells.mesh, zUp = false, hidden = buriedTest(shapes, meshFor))
        return PrintPackage(shells, options(shapes, meshFor, each, shells.mesh), plan, refined = false)
    }

    /**
     * Full version: parts are fused with CSG (within [timeBudgetMs]; past it the remaining parts stay separate
     * shells), and the recipe comes from the real outer surface. [quickPkg] lets the colour parts be reused.
     */
    fun build(shapes: List<Shape>, meshFor: (Shape) -> Mesh, timeBudgetMs: Long = 10_000, quickPkg: PrintPackage? = null): PrintPackage {
        val all = Solidify.run(shapes, meshFor, timeBudgetMs = timeBudgetMs, onTimeout = quickPkg?.let { q -> { q.solid } })
        if (all.timedOut && quickPkg != null) return PrintPackage(all, quickPkg.options, quickPkg.plan, refined = true)
        val opts = (quickPkg ?: quick(shapes, meshFor)).options
        val plan = PrintAdvisor.analyse(all.mesh, zUp = false)
        return PrintPackage(all, opts, plan, refined = true)
    }

    /** Both print modes from the per-part shells [each] (index-aligned with [shapes]). */
    fun options(shapes: List<Shape>, meshFor: (Shape) -> Mesh, each: List<SolidMesh>, all: SolidMesh): Map<PrintMode, PrintOption> {
        val b = shapes.map { Transforms.bounds(it, meshFor(it)) }
        val height = (b.maxOf { it[4] } - b.minOf { it[1] }).toDouble()
        fun weight(group: List<Int>) = group.sumOf { shapes[it].scale.let { s -> (s.x * s.y * s.z).toDouble() } }
        // one colour: everything in the toy's main colour, a single body for one filament
        val main = shapes.indices.groupBy { shapes[it].color }.maxBy { weight(it.value) }.key
        val one = PrintOption(PrintMode.ONE_COLOUR, listOf(BambuProjectExporter.Part(colourName(main), main, all)),
            PrintEstimate.of(all, height, 0), emptyList())
        // colourful: reduced palette, most used colour first -> filament slot 1
        val cp = ColourPlanner.plan(shapes, meshFor)
        val byColour = cp.shapes.indices.groupBy { cp.shapes[it].color }.entries.sortedByDescending { weight(it.value) }
        val parts = byColour.map { (c, idx) -> BambuProjectExporter.Part(colourName(c), c, Solidify.concat(idx.map { each[it] })) }
        val changes = ColourPlanner.colourChanges(cp.shapes, meshFor)
        val colourful = PrintOption(PrintMode.COLOURFUL, parts, PrintEstimate.of(all, height, changes), cp.merges)
        return mapOf(PrintMode.ONE_COLOUR to one, PrintMode.COLOURFUL to colourful)
    }

    /** Point (world, Y-up) clearly inside some solid primitive of the scene (box test for blocks, ellipsoid for balls). */
    fun buriedTest(shapes: List<Shape>, meshFor: (Shape) -> Mesh): (Float, Float, Float) -> Boolean {
        val m = 0.3f
        val solids = shapes.filter { it.type == ShapeType.SPHERE || ((it.type == ShapeType.CUBE || it.type == ShapeType.CYLINDER) && it.rotation == Vec3()) }
            .map { it.type to Transforms.worldBounds(it, meshFor(it)) }
        return { x, y, z ->
            solids.any { (type, b) ->
                if (x <= b[0] + m || x >= b[3] - m || y <= b[1] + m || y >= b[4] - m || z <= b[2] + m || z >= b[5] - m) false
                else {
                    val dx = (x - (b[0] + b[3]) / 2) / ((b[3] - b[0]) / 2); val dy = (y - (b[1] + b[4]) / 2) / ((b[4] - b[1]) / 2)
                    val dz = (z - (b[2] + b[5]) / 2) / ((b[5] - b[2]) / 2)
                    when (type) {
                        ShapeType.SPHERE -> dx * dx + dy * dy + dz * dz < 0.95f
                        ShapeType.CYLINDER -> dx * dx + dz * dz < 0.95f
                        else -> true
                    }
                }
            }
        }
    }
}
