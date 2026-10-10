package com.kidtoy

import com.kidtoy.text.Text3D
import com.kidtoy.text.TrueTypeFont
import org.junit.Test
import java.io.File

/** Writes glyph outlines and top-cap triangles to build/glyphs/ for visual inspection (tools/render_glyphs.py). */
class GlyphDumpTest {
    @Test fun dump() {
        val f = TrueTypeFont(File("src/main/assets/fonts/LilitaOne-Regular.ttf").readBytes())
        Text3D.font = f
        val out = File("build/glyphs").apply { mkdirs() }
        for (word in listOf("Mika", "aeio", "Jürgen")) {
            val sb = StringBuilder("{\"contours\":[")
            var pen = 0.0; var first = true
            for (ch in word) {
                val g = f.glyphIndex(ch.code)
                for (c in f.contours(g)) {
                    if (!first) sb.append(','); first = false
                    sb.append(c.joinToString(",", "[", "]") { "[${it[0] + pen},${it[1]}]" })
                }
                pen += f.advance(g) + f.unitsPerEm * 0.06
            }
            sb.append("],\"tris\":[")
            val m = Text3D.buildUncached(f, word)!!.mesh.positions
            var t = 0; first = true
            while (t < m.size) {
                if (m[t + 2] > 0.49f && m[t + 5] > 0.49f && m[t + 8] > 0.49f) {
                    if (!first) sb.append(','); first = false
                    sb.append("[${m[t]},${m[t + 1]},${m[t + 3]},${m[t + 4]},${m[t + 6]},${m[t + 7]}]")
                }
                t += 9
            }
            sb.append("]}")
            File(out, "$word.json").writeText(sb.toString())
        }
    }
}
