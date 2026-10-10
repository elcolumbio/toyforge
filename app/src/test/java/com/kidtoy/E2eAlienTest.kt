package com.kidtoy

import com.kidtoy.ai.AnthropicToyDesigner
import com.kidtoy.ai.MockAnthropicTransport
import com.kidtoy.geometry.ShapeMeshes
import com.kidtoy.io.BambuProjectExporter
import com.kidtoy.io.PrintPackager
import com.kidtoy.io.StlExporter
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
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The full kid story on the JVM: "a blue alien with big ears" via the (mocked) Claude client, bigger ears,
 * a recolour, a move, a "Mika" nameplate, then STL + Bambu 3MF export with watertight checks.
 * Writes the files to build/e2e/ so they can be sliced with OrcaSlicer's CLI.
 */
class E2eAlienTest {
    private val meshFor: (Shape) -> com.kidtoy.geometry.Mesh = { ShapeMeshes.of(it, emptyMap()) }

    @Test fun blueAlienWithNameplateExportsPrintableFiles() = runBlocking {
        val scene = AlienScene.make()
        val t0 = System.currentTimeMillis()
        val pkg = PrintPackager.build(scene, meshFor)
        println("e2e: ${System.currentTimeMillis() - t0} ms fused=${pkg.solid.fusedGroups} fallback=${pkg.solid.fallbackGroups} loose=${pkg.solid.looseShells} tris=${pkg.solid.mesh.triangleCount} " +
            "parts=${pkg.parts.size} plan=${pkg.plan}")
        assertEquals(0, pkg.solid.mesh.openEdgeCount())
        assertTrue(pkg.solid.mesh.volume() > 0)
        for (p in pkg.parts) {
            assertEquals("part ${p.name} open edges", 0, p.mesh.openEdgeCount())
            assertTrue(p.mesh.volume() > 0)
        }

        val out = File("build/e2e").apply { mkdirs() }
        val stl = StlExporter.toBytes(pkg.solid.mesh, "Mika's alien")
        File(out, "mika-alien.stl").writeBytes(stl)
        assertEquals(84 + 50 * pkg.solid.mesh.triangleCount, stl.size)
        val mf = BambuProjectExporter.toBytes(pkg.parts, pkg.plan, "Mika's Blue Alien")
        File(out, "mika-alien.3mf").writeBytes(mf)
        val names = ArrayList<String>()
        ZipInputStream(mf.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; names.add(e.name) } }
        for (n in listOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model", "3D/_rels/3dmodel.model.rels", "3D/Objects/object_1.model", "Metadata/model_settings.config"))
            assertTrue("3mf has $n", n in names)
        File(out, "scene.json").writeText(kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Shape.serializer()), scene))
    }
}
