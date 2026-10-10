package com.kidtoy

import com.kidtoy.ai.AnthropicToyDesigner
import com.kidtoy.ai.MockAnthropicTransport
import com.kidtoy.geometry.ShapeMeshes
import com.kidtoy.model.NameTag
import com.kidtoy.model.Palette
import com.kidtoy.model.SceneOps
import com.kidtoy.model.Shape
import com.kidtoy.model.Vec3
import com.kidtoy.text.Text3D
import com.kidtoy.text.TrueTypeFont
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/** The e2e story scene: mocked AI alien, bigger ears, purple belly, moved left, "Mika" nameplate. */
object AlienScene {
    val meshFor: (Shape) -> com.kidtoy.geometry.Mesh = { ShapeMeshes.of(it, emptyMap()) }

    fun make(): List<Shape> = runBlocking {
        Text3D.font = TrueTypeFont(File("src/main/assets/fonts/LilitaOne-Regular.ttf").readBytes())
        val ai = AnthropicToyDesigner("k", "claude-sonnet-5-5", transport = MockAnthropicTransport(0))
        val d = ai.design("a blue alien with big ears")
        assertEquals("Blue Alien Buddy", d.name)
        var scene = SceneOps.placeGroup(d.shapes, meshFor)
        assertTrue(SceneOps.check(scene, meshFor).ok)

        // 1) bigger ears: the two large blue ellipsoids high up on the sides (and their pink insides)
        val ears = scene.filter { it.position.y > 60f && kotlin.math.abs(it.position.x) > 25f }
        assertEquals(4, ears.size)
        scene = scene.map { s -> if (s in ears) s.copy(scale = s.scale * 1.25f, position = s.position.copy(x = s.position.x * 1.12f)) else s }
        // 2) recolour the belly purple
        val belly = scene.first { it.color == Palette.colors[4] }
        scene = scene.map { if (it.id == belly.id) it.copy(color = Palette.colors[6]) else it }
        // 3) move the whole toy a bit to the left
        scene = scene.map { it.copy(position = it.position + Vec3(-15f, 0f, 0f)) }
        // 4) nameplate
        scene = NameTag.nameplate(scene, meshFor, "Mika", Palette.colors[7], Palette.colors[9])!!
        val report = SceneOps.check(scene, meshFor)
        assertTrue("printable: ${report.issues}", report.ok)

        scene
    }
}
