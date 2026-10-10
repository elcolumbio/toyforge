package com.kidtoy

import com.kidtoy.geometry.Csg
import com.kidtoy.geometry.Primitives
import com.kidtoy.geometry.Solidify
import com.kidtoy.geometry.Transforms
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class GeometryTest {
    /** Primitive scaled to 1 x 1 x 1 cm (unit volume = 1000 mm^3), the scale kids actually build at. */
    private fun solid(type: ShapeType) = Solidify.weldAndTriangulate(
        Csg.fromMesh(Transforms.toWorld(Shape("p", type, Vec3(), Vec3(10f, 10f, 10f), color = 1), Primitives.get(type)), 1)
    )
    private fun unitVolume(type: ShapeType) = solid(type).volume() / 1000.0

    @Test fun everyPrimitiveIsClosedAndOutward() {
        val expected = mapOf(
            ShapeType.CUBE to 1.0, ShapeType.SPHERE to PI / 6, ShapeType.CYLINDER to PI / 4,
            ShapeType.CONE to PI / 12, ShapeType.TORUS to 2 * PI * PI * 0.35 * 0.15 * 0.15, ShapeType.PYRAMID to 1.0 / 3,
        )
        for (t in ShapeType.values().filter { it != ShapeType.MESH && it != ShapeType.TEXT }) {
            val m = solid(t)
            assertEquals("$t open edges", 0, m.openEdgeCount())
            assertTrue("$t volume positive (outward normals): ${m.volume()}", m.volume() > 0)
            expected[t]?.let { assertEquals("$t volume", it, m.volume() / 1000.0, it * 0.03) }
            val b = Primitives.get(t).bounds()
            for (a in 0 until 3) {
                assertTrue("$t fits unit box", b[a] >= -0.5001f && b[a + 3] <= 0.5001f)
            }
        }
    }

    private fun shape(id: String, t: ShapeType, pos: Vec3, size: Vec3, rot: Vec3 = Vec3(), color: Int = 0xFFFF0000.toInt()) =
        Shape(id, t, pos, size, rot, color)

    @Test fun overlappingCubesFuseIntoOneWatertightSolid() {
        val a = shape("a", ShapeType.CUBE, Vec3(0f, 10f, 0f), Vec3(20f, 20f, 20f))
        val b = shape("b", ShapeType.CUBE, Vec3(10f, 10f, 0f), Vec3(20f, 20f, 20f), color = 0xFF00FF00.toInt())
        val r = Solidify.run(listOf(a, b), { Primitives.get(it.type) })
        assertEquals(1, r.fusedGroups)
        assertEquals(0, r.mesh.openEdgeCount())
        assertEquals(30.0 * 20 * 20, r.mesh.volume(), 1.0)
        assertTrue("keeps both colours", r.mesh.triangleColors.toSet().size == 2)
    }

    @Test fun sphereOnRotatedCylinderFusesWatertight() {
        val parts = listOf(
            shape("c", ShapeType.CYLINDER, Vec3(0f, 10f, 0f), Vec3(30f, 20f, 30f)),
            shape("s", ShapeType.SPHERE, Vec3(5f, 22f, 3f), Vec3(24f, 24f, 24f)),
            shape("w", ShapeType.CYLINDER, Vec3(-12f, 9f, 14f), Vec3(18f, 10f, 18f), Vec3(90f, 0f, 0f)),
        )
        val r = Solidify.run(parts, { Primitives.get(it.type) })
        assertEquals(0, r.mesh.openEdgeCount())
        val sum = unitVolume(ShapeType.CYLINDER) * 30 * 20 * 30 + unitVolume(ShapeType.SPHERE) * 24 * 24 * 24 +
            unitVolume(ShapeType.CYLINDER) * 18 * 10 * 18
        val v = r.mesh.volume()
        assertTrue("union smaller than sum ($v < $sum)", v < sum)
        assertTrue("union bigger than biggest part", v > unitVolume(ShapeType.CYLINDER) * 30 * 20 * 30)
    }

    @Test fun separatePartsAreNotFused() {
        val a = shape("a", ShapeType.SPHERE, Vec3(-40f, 10f, 0f), Vec3(20f, 20f, 20f))
        val b = shape("b", ShapeType.TORUS, Vec3(40f, 3f, 0f), Vec3(30f, 20f, 30f))
        val r = Solidify.run(listOf(a, b), { Primitives.get(it.type) })
        assertEquals(0, r.fusedGroups)
        assertEquals(0, r.mesh.openEdgeCount())
    }
}
