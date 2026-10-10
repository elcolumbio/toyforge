package com.kidtoy

import com.kidtoy.ai.AiShapeParser
import com.kidtoy.ai.Templates
import com.kidtoy.geometry.Primitives
import com.kidtoy.geometry.Solidify
import com.kidtoy.model.SceneOps
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** End-to-end robustness: realistic toys and random piles must export as closed solids. */
class TemplateSolidTest {
    private val meshFor = { s: Shape -> Primitives.get(s.type) }

    @Test fun everyStarterToyExportsWatertight() {
        for (t in Templates.all) {
            val shapes = SceneOps.placeGroup(AiShapeParser.parse(t.json).shapes, meshFor)
            val start = System.currentTimeMillis()
            val r = Solidify.run(shapes, meshFor)
            val ms = System.currentTimeMillis() - start
            println("template ${t.key}: ${shapes.size} parts -> ${r.mesh.triangleCount} tris, fused=${r.fusedGroups} fallback=${r.fallbackGroups}, ${ms} ms")
            assertEquals("${t.key} open edges", 0, r.mesh.openEdgeCount())
            assertTrue(r.mesh.volume() > 0)
            assertTrue("${t.key} printable", SceneOps.check(shapes, meshFor).ok)
        }
    }

    @Test fun randomPilesExportWatertight() {
        val rnd = Random(42)
        val types = ShapeType.values().filter { it != ShapeType.MESH && it != ShapeType.TEXT }
        var bad = 0; var fused = 0; var fallback = 0
        repeat(25) { n ->
            val shapes = (0 until 4).map { i ->
                Shape(
                    "s$n-$i", types[rnd.nextInt(types.size)],
                    Vec3(rnd.nextInt(-3, 4) * 5f, 10f + rnd.nextInt(0, 4) * 5f, rnd.nextInt(-3, 4) * 5f),
                    Vec3(rnd.nextInt(10, 40).toFloat(), rnd.nextInt(10, 40).toFloat(), rnd.nextInt(10, 40).toFloat()),
                    Vec3(rnd.nextInt(0, 24) * 15f, rnd.nextInt(0, 24) * 15f, 0f), 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF),
                )
            }
            val r = Solidify.run(shapes, meshFor)
            val open = r.mesh.openEdgeCount()
            fused += r.fusedGroups; fallback += r.fallbackGroups
            if (open != 0) { bad++; println("pile $n: open=$open") }
            assertTrue(r.mesh.volume() > 0)
        }
        println("random piles: fused=$fused fallback=$fallback")
        assertEquals("random piles with open edges", 0, bad)
    }
}
