package com.kidtoy

import com.kidtoy.geometry.Primitives
import com.kidtoy.geometry.ShapeMeshes
import com.kidtoy.io.PrintMode
import com.kidtoy.io.PrintPackager
import com.kidtoy.model.NameTag
import com.kidtoy.model.SceneOps
import com.kidtoy.text.Text3D
import com.kidtoy.text.TrueTypeFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class KeychainTest {
    companion object {
        @BeforeClass @JvmStatic fun loadFont() {
            if (Text3D.font == null) Text3D.font = TrueTypeFont(File("src/main/assets/fonts/LilitaOne-Regular.ttf").readBytes())
        }
    }

    @Test fun keychainIsOnePrintableFlatPieceWithARing() {
        val s = NameTag.keychain("Mika", 0xFFFFFFFF.toInt(), 0xFFB15CFF.toInt())!!
        val meshFor = { sh: com.kidtoy.model.Shape -> ShapeMeshes.of(sh, emptyMap()) }
        val report = SceneOps.check(s, meshFor)
        assertTrue("issues ${report.issues}", report.ok)
        val b = SceneOps.groupBounds(s, meshFor)
        println("keychain size %.0f x %.0f x %.1f mm".format(b[3] - b[0], b[5] - b[2], b[4] - b[1]))
        assertEquals(0f, b[1], 0.01f)
        assertTrue("flat: height ${b[4] - b[1]}", b[4] - b[1] <= NameTag.KEYCHAIN_THICKNESS + NameTag.LETTER_RELIEF + 0.01f)
        assertTrue("centred", kotlin.math.abs(b[0] + b[3]) < 1f)
        val pkg = PrintPackager.quick(s, meshFor)
        val one = pkg.options.getValue(PrintMode.ONE_COLOUR).estimate
        val col = pkg.options.getValue(PrintMode.COLOURFUL).estimate
        println("keychain one colour: $one; colourful: $col; supports=${pkg.plan}")
        assertTrue(one.minutes < 45)
        assertEquals(2, pkg.options.getValue(PrintMode.COLOURFUL).parts.size)
        // the ring's hole stays open: hole radius 0.2 x 20 mm = 4 mm, the tag starts 6 mm from the ring centre
        val ring = s.first { it.type == com.kidtoy.model.ShapeType.TORUS }
        val tag = s.first { it.type == com.kidtoy.model.ShapeType.CUBE }
        val tagLeft = tag.position.x - tag.scale.x / 2
        assertTrue(tagLeft - ring.position.x >= 0.2f * ring.scale.x + 1.9f)
        Primitives.get(com.kidtoy.model.ShapeType.TORUS)
    }
}
