package com.kidtoy

import com.kidtoy.geometry.Csg
import com.kidtoy.geometry.ShapeMeshes
import com.kidtoy.geometry.Solidify
import com.kidtoy.geometry.Transforms
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.model.Vec3
import com.kidtoy.text.Text3D
import com.kidtoy.text.TrueTypeFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class TextTest {
    companion object {
        @BeforeClass @JvmStatic fun loadFont() {
            Text3D.font = TrueTypeFont(File("src/main/assets/fonts/LilitaOne-Regular.ttf").readBytes())
        }
    }

    private fun textShape(t: String, w: Float = 60f) = Shape("t", ShapeType.TEXT, Vec3(0f, 9f, 0f), Vec3(w, 18f, 6f), color = 0xFFFF0000.toInt(), text = t)

    @Test fun lettersAreWatertightSolids() {
        for (word in listOf("Mika", "ToyForge", "B8@", "Jürgen", "Zoë 2026", "Ä Ö Ü ß", "OBAQ%&")) {
            val built = Text3D.build(word)
            assertNotNull(word, built)
            val b = built!!.mesh.bounds()
            for (a in 0 until 3) assertTrue("$word in unit box", b[a] >= -0.5001f && b[a + 3] <= 0.5001f)
            assertTrue("$word aspect ${built.aspect}", built.aspect > 0.5f)
            val world = Transforms.toWorld(textShape(word), built.mesh)
            val solid = Solidify.weldAndTriangulate(Csg.fromMesh(world, 1))
            assertEquals("$word open edges", 0, solid.openEdgeCount())
            assertTrue("$word volume ${solid.volume()}", solid.volume() > 0)
        }
    }

    @Test fun lettersFillAPlausibleShareOfTheirBox() {
        // the 'O' is a ring: hole must really be a hole (volume well below the full bounding box)
        val o = Text3D.build("O")!!
        val solid = Solidify.weldAndTriangulate(Csg.fromMesh(Transforms.toWorld(textShape("O", 20f).copy(scale = Vec3(20f, 20f, 10f)), o.mesh), 1))
        val fill = solid.volume() / (20.0 * 20 * 10)
        assertTrue("O fill $fill", fill in 0.3..0.85)
    }

    @Test fun cleanKeepsFriendlyCharacters() {
        assertEquals("Mika", Text3D.clean("  Mika \n"))
        assertEquals(Text3D.MAX_CHARS, Text3D.clean("abcdefghijklmnopqrstuvwxyz").length)
        assertNull(Text3D.build("   "))
    }

    @Test fun nameplateFusesWithLetters() {
        val plate = Shape("p", ShapeType.CUBE, Vec3(0f, 2f, 0f), Vec3(80f, 4f, 28f), color = -1)
        val letters = Shape("l", ShapeType.TEXT, Vec3(0f, 5.5f, 0f), Vec3(66f, 4f, 18f), Vec3(-90f, 0f, 0f), color = 0xFF5B6CFF.toInt(), text = "Mika")
        val r = Solidify.run(listOf(plate, letters), { ShapeMeshes.of(it, emptyMap()) })
        println("nameplate: fused=${r.fusedGroups} fallback=${r.fallbackGroups} tris=${r.mesh.triangleCount}")
        assertEquals(0, r.mesh.openEdgeCount())
        assertTrue(r.mesh.volume() > 80.0 * 4 * 28)
    }
}
