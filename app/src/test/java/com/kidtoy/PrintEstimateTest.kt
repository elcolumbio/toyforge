package com.kidtoy

import com.kidtoy.io.BambuProjectExporter
import com.kidtoy.io.PrintEstimate
import com.kidtoy.io.PrintMode
import com.kidtoy.io.PrintPackager
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.model.Vec3
import org.junit.Test
import java.io.File

/**
 * Writes calibration models to build/calib/ (sliced by tools/slicetest/calibrate.sh) and prints the raw
 * quantities the estimate uses, so the constants in [PrintEstimate] can be fitted to OrcaSlicer's numbers.
 */
class PrintEstimateTest {
    /** OrcaSlicer 2.4.2 CLI results for the models written below (minutes, grams incl. purge, swaps). */
    private val orca = mapOf(
        "alien-ONE_COLOUR" to Triple(169.3, 73.3, 0), "alien-COLOURFUL" to Triple(510.8, 129.7, 195),
        "alien70-ONE_COLOUR" to Triple(82.0, 31.6, 0), "alien50-ONE_COLOUR" to Triple(45.2, 14.7, 0),
        "cube20-ONE_COLOUR" to Triple(16.0, 4.3, 0),
        // out-of-sample check (not used for the fit): 60 % alien, 4 colours
        "alien60-COLOURFUL" to Triple(260.4, 54.1, 116),
    )

    private fun scaled(s: List<Shape>, f: Float) = s.map { it.copy(position = it.position * f, scale = it.scale * f) }

    @Test fun writeCalibrationModels() {
        val alien = AlienScene.make()
        val cube = listOf(Shape("c", ShapeType.CUBE, Vec3(0f, 10f, 0f), Vec3(20f, 20f, 20f), color = 0xFF5B6CFF.toInt()))
        val out = File("build/calib").apply { mkdirs() }
        for ((name, scene) in listOf("alien" to alien, "alien70" to scaled(alien, 0.7f), "alien50" to scaled(alien, 0.5f), "alien60" to scaled(alien, 0.6f), "cube20" to cube)) {
            val pkg = PrintPackager.quick(scene, AlienScene.meshFor)
            for (mode in PrintMode.values()) {
                val o = pkg.options.getValue(mode)
                if (name != "alien" && name != "alien60" && mode == PrintMode.COLOURFUL) continue
                if (name == "alien60" && mode == PrintMode.ONE_COLOUR) continue
                File(out, "$name-${mode.name.lowercase()}.3mf").writeBytes(BambuProjectExporter.toBytes(o.parts, pkg.plan, name))
                val m = pkg.solid.mesh
                println("calib: $name ${mode.name} vol=${"%.0f".format(m.volume())} area=${"%.0f".format(PrintEstimate.area(m))} " +
                    "changes=${o.estimate.colourChanges} colours=${o.parts.size} merges=${o.merges.map { "%06X->%06X".format(it.first and 0xFFFFFF, it.second and 0xFFFFFF) }} " +
                    "est=${o.estimate}")
                val ref = orca["$name-${mode.name}"] ?: continue
                println("calib: $name ${mode.name} estimate ${o.estimate.minutes} min / ${o.estimate.grams} g / ${o.estimate.colourChanges} swaps vs Orca ${ref.first} min / ${ref.second} g / ${ref.third} swaps")
                org.junit.Assert.assertEquals("$name $mode minutes", ref.first, o.estimate.minutes.toDouble(), ref.first * 0.15 + 3)
                org.junit.Assert.assertEquals("$name $mode grams", ref.second, o.estimate.grams.toDouble(), ref.second * 0.15 + 1)
                org.junit.Assert.assertEquals("$name $mode swaps", ref.third.toDouble(), o.estimate.colourChanges.toDouble(), ref.third * 0.15 + 1)
            }
        }
    }

    @Test fun modesAndShrinkSuggestion() {
        val pkg = PrintPackager.quick(AlienScene.make(), AlienScene.meshFor)
        val one = pkg.options.getValue(PrintMode.ONE_COLOUR); val col = pkg.options.getValue(PrintMode.COLOURFUL)
        org.junit.Assert.assertEquals(1, one.parts.size)
        org.junit.Assert.assertEquals(0, one.estimate.colourChanges)
        org.junit.Assert.assertTrue(col.parts.size <= com.kidtoy.io.ColourPlanner.MAX_COLOURS)
        // eye glints (white) go into the dark eyes, not into the blue head
        org.junit.Assert.assertTrue(col.merges.contains(0xFFFFFFFF.toInt() to 0xFF37474F.toInt()))
        org.junit.Assert.assertTrue(col.estimate.colourChanges < 361) // 5 colours unmerged: 361 swaps in Orca
        org.junit.Assert.assertNull("2h49 one-colour print needs no shrinking", PrintEstimate.shrinkSuggestion(one.estimate))
        val (f, e) = PrintEstimate.shrinkSuggestion(col.estimate)!!
        println("shrink: colourful ${col.estimate.minutes} min -> ${(f * 100).toInt()}% = ${e.minutes} min / ${e.grams} g / ${e.colourChanges} swaps")
        org.junit.Assert.assertTrue(e.minutes <= maxOf(PrintEstimate.TARGET_MIN.toDouble(), col.estimate.minutes * 0.55) && f in 0.5f..0.95f)
    }
}
