package com.kidtoy.io

import com.kidtoy.geometry.Mesh
import com.kidtoy.geometry.SolidMesh
import com.kidtoy.geometry.Transforms
import com.kidtoy.model.Shape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** How the toy is sent to the printer. */
enum class PrintMode(val kidLabel: String) {
    /** One body in one filament: no AMS, no colour changes, by far the fastest. */
    ONE_COLOUR("One colour (fast)"),
    /** Up to [ColourPlanner.MAX_COLOURS] AMS colours, tiny decorations merged into their neighbour. */
    COLOURFUL("Colourful (AMS)"),
}

/**
 * Picks the colours for a multi-colour print so it needs as few filament swaps as possible:
 *  1. tiny decorations (eye glints) take the colour of the part they sit on,
 *  2. while there are more than [MAX_COLOURS] colours, the colour closest to another one is merged into it
 *     (classic palette reduction, so a purple belly becomes blue rather than eyes disappearing).
 */
object ColourPlanner {
    const val MAX_COLOURS = 4
    const val TINY_MM3 = 300.0

    class Plan(val shapes: List<Shape>, val merges: List<Pair<Int, Int>>)

    fun plan(shapes: List<Shape>, meshFor: (Shape) -> Mesh, maxColours: Int = MAX_COLOURS): Plan {
        val boxes = shapes.map { Transforms.bounds(it, meshFor(it)) }
        fun vol(i: Int) = shapes[i].scale.let { abs(it.x * it.y * it.z).toDouble() } * 0.52 // ~ellipsoid share of its box
        val colour = shapes.map { it.color }.toMutableList()
        val merges = ArrayList<Pair<Int, Int>>()
        // 1) tiny decorations (< 0.2% of the toy, e.g. eye glints) adopt the colour of the part they sit in
        val tinyLimit = minOf(TINY_MM3, shapes.indices.sumOf { vol(it) } * 0.002)
        for (i in shapes.indices) {
            if (vol(i) >= tinyLimit) continue
            val c = Transforms.center(boxes[i])
            val host = shapes.indices.filter { it != i && vol(it) > vol(i) && contains(boxes[it], c) }.minByOrNull { vol(it) } ?: continue
            if (colour[host] != colour[i]) { merges.add(colour[i] to colour[host]); colour[i] = colour[host] }
        }
        // 2) cap the number of colours by merging the two closest colours (smaller one into bigger one)
        while (colour.distinct().size > maxColours) {
            val vols = colour.indices.groupBy { colour[it] }.mapValues { e -> e.value.sumOf { vol(it) } }
            val cs = vols.keys.toList()
            var best: Pair<Int, Int>? = null; var bestD = Double.MAX_VALUE
            for (a in cs) for (b in cs) if (a != b && vols[a]!! <= vols[b]!!) {
                val d = dist(a, b); if (d < bestD) { bestD = d; best = a to b }
            }
            val (from, to) = best ?: break
            merges.add(from to to)
            for (i in colour.indices) if (colour[i] == from) colour[i] = to
        }
        return Plan(shapes.mapIndexed { i, s -> if (s.color == colour[i]) s else s.copy(color = colour[i]) }, merges)
    }

    private fun contains(b: FloatArray, p: com.kidtoy.model.Vec3) =
        p.x in b[0]..b[3] && p.y in b[1]..b[4] && p.z in b[2]..b[5]

    private fun overlap(a: FloatArray, b: FloatArray) =
        a[0] <= b[3] && b[0] <= a[3] && a[1] <= b[4] && b[1] <= a[4] && a[2] <= b[5] && b[2] <= a[5]

    private fun dist(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255); val dg = ((a shr 8) and 255) - ((b shr 8) and 255); val db = (a and 255) - (b and 255)
        return sqrt((dr * dr * 2 + dg * dg * 4 + db * db * 3).toDouble()) // perceptual weights
    }

    /**
     * Filament swaps a slicer needs: on every layer each extra colour costs one swap
     * (the first colour of a layer is the one the previous layer ended with).
     */
    fun colourChanges(shapes: List<Shape>, meshFor: (Shape) -> Mesh, layer: Float = 0.2f): Int {
        if (shapes.isEmpty()) return 0
        val boxes = shapes.map { Transforms.bounds(it, meshFor(it)) }
        val minY = boxes.minOf { it[1] }; val maxY = boxes.maxOf { it[4] }
        val layers = ceil((maxY - minY) / layer).toInt().coerceAtLeast(1)
        val sets = Array(layers) { HashSet<Int>() }
        for ((i, s) in shapes.withIndex()) {
            val a = floor((boxes[i][1] - minY) / layer).toInt().coerceIn(0, layers - 1)
            val b = (ceil((boxes[i][4] - minY) / layer).toInt() - 1).coerceIn(0, layers - 1)
            for (l in a..b) sets[l].add(s.color)
        }
        return sets.sumOf { maxOf(it.size - 1, 0) }
    }
}

/** Print time / filament estimate, calibrated against OrcaSlicer 2.4.2 (P1S, 0.20 mm Standard, Bambu PLA Basic). */
data class PrintEstimate(val minutes: Int, val grams: Int, val colourChanges: Int,
                         val volumeMm3: Double = 0.0, val areaMm2: Double = 0.0, val heightMm: Double = 0.0) {
    /** The same toy scaled by [f] in every direction (volume f^3, surface f^2, layers and swaps f). */
    fun scaled(f: Double) = compute(volumeMm3 * f * f * f, areaMm2 * f * f, heightMm * f, (colourChanges * f).toInt())

    fun kidTime(): String = when {
        minutes < 60 -> "about $minutes min"
        minutes < 90 -> "about 1 hour"
        else -> "about ${(minutes + 30) / 60} hours"
    }

    companion object {
        // Least-squares fit to OrcaSlicer 2.4.2 CLI slices (P1S 0.4, 0.20 mm Standard, Bambu PLA Basic, our overrides):
        // alien 100/70/50 % and a 20 mm cube in one colour, and the 4-colour alien (195 swaps).
        // tools/slicetest/calibrate.sh + PrintEstimateTest reproduce the numbers.
        const val MIN_PER_GRAM = 1.827
        const val MIN_PER_LAYER = 0.0806
        const val MIN_PER_CHANGE = 1.75
        const val GRAMS_PER_CHANGE = 0.289
        const val SHELL_MM = 0.886
        const val INFILL = 0.160
        const val PLA_G_PER_MM3 = 1.24e-3

        /** Model grams from surface and volume: walls/skins are solid, the inside is [INFILL] gyroid. */
        fun grams(volumeMm3: Double, areaMm2: Double): Double {
            val shell = minOf(areaMm2 * SHELL_MM, volumeMm3)
            return PLA_G_PER_MM3 * (shell + (volumeMm3 - shell) * INFILL)
        }

        fun of(mesh: SolidMesh, heightMm: Double, changes: Int) = compute(abs(mesh.volume()), area(mesh), heightMm, changes)

        fun compute(volume: Double, area: Double, heightMm: Double, changes: Int): PrintEstimate {
            val g = grams(volume, area)
            val layers = heightMm / 0.2
            val min = g * MIN_PER_GRAM + layers * MIN_PER_LAYER + changes * MIN_PER_CHANGE
            return PrintEstimate(min.toInt().coerceAtLeast(1), (g + changes * GRAMS_PER_CHANGE).toInt().coerceAtLeast(1), changes, volume, area, heightMm)
        }

        /**
         * Long prints (> [LONG_MIN]) get a "make it smaller" offer: the biggest size that prints in about [TARGET_MIN]
         * (or at least ~45% faster when colour swaps dominate), never below [MIN_SHRINK] so details stay printable.
         */
        const val LONG_MIN = 180
        const val TARGET_MIN = 150
        const val MIN_SHRINK = 0.5

        fun shrinkSuggestion(e: PrintEstimate): Pair<Float, PrintEstimate>? {
            if (e.minutes <= LONG_MIN || e.volumeMm3 <= 0) return null
            val target = maxOf(TARGET_MIN.toDouble(), e.minutes * 0.55)
            var f = 0.95
            while (f >= MIN_SHRINK - 1e-9) {
                val s = e.scaled(f)
                if (s.minutes <= target) return f.toFloat() to s
                f -= 0.05
            }
            return MIN_SHRINK.toFloat() to e.scaled(MIN_SHRINK)
        }

        fun area(m: SolidMesh): Double {
            var a = 0.0
            val v = m.vertices
            for (t in 0 until m.triangleCount) {
                val i = m.triangles[t * 3] * 3; val j = m.triangles[t * 3 + 1] * 3; val k = m.triangles[t * 3 + 2] * 3
                val ux = v[j] - v[i]; val uy = v[j + 1] - v[i + 1]; val uz = v[j + 2] - v[i + 2]
                val wx = v[k] - v[i]; val wy = v[k + 1] - v[i + 1]; val wz = v[k + 2] - v[i + 2]
                val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
                a += 0.5 * sqrt((cx * cx + cy * cy + cz * cz).toDouble())
            }
            return a
        }
    }
}
