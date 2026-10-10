package com.kidtoy

import com.kidtoy.geometry.Primitives
import com.kidtoy.geometry.Solidify
import com.kidtoy.io.MeshImporter
import com.kidtoy.io.ProjectJson
import com.kidtoy.io.StlExporter
import com.kidtoy.io.ThreeMfExporter
import com.kidtoy.model.ImportedMeshRef
import com.kidtoy.model.Project
import com.kidtoy.model.SceneOps
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.model.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class ExportImportTest {
    private val cube = Shape("a", ShapeType.CUBE, Vec3(0f, 10f, 0f), Vec3(20f, 20f, 30f), color = 0xFFFF0000.toInt())

    @Test fun binaryStlHasCorrectLayoutAndZUp() {
        val mesh = Solidify.run(listOf(cube), { Primitives.get(it.type) }).mesh
        val bytes = StlExporter.toBytes(mesh)
        assertEquals(84 + 50 * 12, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12, bb.getInt(80))
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var signedVolume = 0.0
        for (t in 0 until 12) {
            val base = 84 + t * 50
            val v = FloatArray(9) { bb.getFloat(base + 12 + it * 4) }
            for (k in 0 until 3) {
                minZ = minOf(minZ, v[k * 3 + 2]); maxZ = maxOf(maxZ, v[k * 3 + 2])
                minY = minOf(minY, v[k * 3 + 1]); maxY = maxOf(maxY, v[k * 3 + 1])
            }
            signedVolume += (v[0] * (v[4] * v[8] - v[5] * v[7]) - v[1] * (v[3] * v[8] - v[5] * v[6]) + v[2] * (v[3] * v[7] - v[4] * v[6])) / 6.0
            // stored normal agrees with winding
            val n = FloatArray(3) { bb.getFloat(base + it * 4) }
            val cx = (v[4] - v[1]) * (v[8] - v[2]) - (v[5] - v[2]) * (v[7] - v[1])
            val cy = (v[5] - v[2]) * (v[6] - v[0]) - (v[3] - v[0]) * (v[8] - v[2])
            val cz = (v[3] - v[0]) * (v[7] - v[1]) - (v[4] - v[1]) * (v[6] - v[0])
            assertTrue(n[0] * cx + n[1] * cy + n[2] * cz > 0)
        }
        assertEquals("sits on the plate (z=0)", 0f, minZ, 1e-4f)
        assertEquals("height becomes Z", 20f, maxZ, 1e-4f)
        assertEquals("depth becomes Y", 30f, maxY - minY, 1e-4f)
        assertEquals(20.0 * 20 * 30, signedVolume, 0.5)
    }

    @Test fun stlRoundTripThroughImporter() {
        val mesh = Solidify.run(listOf(cube), { Primitives.get(it.type) }).mesh
        val m = MeshImporter.read("toy.stl", StlExporter.toBytes(mesh))
        val e = MeshImporter.extents(m)
        assertArrayEquals(floatArrayOf(20f / 30f, 20f / 30f, 1f), e, 1e-4f) // back to Y-up, normalised
    }

    @Test fun asciiStlAndObjImport() {
        val ascii = """solid t
facet normal 0 0 1
outer loop
vertex 0 0 0
vertex 10 0 0
vertex 0 10 0
endloop
endfacet
endsolid t"""
        assertEquals(1, MeshImporter.read("a.stl", ascii.toByteArray()).triangleCount)
        val obj = "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf 1/1/1 2/2/2 3/3/3 4/4/4\n"
        assertEquals(2, MeshImporter.read("q.obj", obj.toByteArray()).triangleCount)
    }

    @Test fun threeMfIsAValidPackage() {
        val mesh = Solidify.run(listOf(cube), { Primitives.get(it.type) }).mesh
        val out = ByteArrayOutputStream()
        ThreeMfExporter.write(mesh, out, "Test & Toy")
        val names = ArrayList<String>(); var model = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names.add(e.name)
                if (e.name == "3D/3dmodel.model") model = z.readBytes().decodeToString()
            }
        }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model")))
        assertEquals(8, Regex("<vertex ").findAll(model).count())
        assertEquals(12, Regex("<triangle ").findAll(model).count())
        assertTrue(model.contains("Test &amp; Toy"))
        assertTrue(model.contains("#FF0000FF"))
    }

    @Test fun projectJsonRoundTrip() {
        val m = Primitives.get(ShapeType.PYRAMID)
        val ref = ProjectJson.meshToRef("m1", "pyr", m)
        val p = Project(name = "X", shapes = listOf(cube), meshes = listOf(ref))
        val back = ProjectJson.decode(ProjectJson.encode(p))
        assertEquals(p, back)
        assertArrayEquals(m.positions, ProjectJson.refToMesh(back.meshes[0]).positions, 0f)
    }

    @Test fun settleKeepsShapesOnThePlate() {
        val sunk = cube.copy(position = Vec3(150f, -30f, 0f))
        val s = SceneOps.settle(sunk, Primitives.get(ShapeType.CUBE))
        assertEquals(10f, s.position.y, 1e-4f)
        assertEquals(90f, s.position.x, 1e-4f)
        val rotated = Shape("r", ShapeType.SPHERE, Vec3(0f, 0f, 0f), Vec3(20f, 20f, 20f), Vec3(33f, 10f, 70f), 0)
        assertEquals(10f, SceneOps.settle(rotated, Primitives.get(ShapeType.SPHERE)).position.y, 0.05f)
    }

    @Test fun printabilityCheckFindsFloatingAndTinyParts() {
        val floating = cube.copy(id = "f", position = Vec3(0f, 80f, 0f))
        val tiny = cube.copy(id = "t", position = Vec3(50f, 0.5f, 0f), scale = Vec3(1f, 1f, 1f))
        val r = SceneOps.check(listOf(cube, floating, tiny), { Primitives.get(it.type) })
        assertTrue(r.issues.contains(SceneOps.Issue.FLOATING))
        assertEquals(setOf("f"), r.floatingIds)
        assertEquals(setOf("t"), r.tinyIds)
        assertTrue(SceneOps.check(listOf(cube), { Primitives.get(it.type) }).ok)
    }
}
