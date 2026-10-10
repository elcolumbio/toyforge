package com.kidtoy.io

import com.kidtoy.geometry.SolidMesh
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exporters convert the app's Y-up scene to the Z-up convention every slicer expects:
 * (x, y, z)_app -> (x, -z, y)_print  (a proper rotation, so triangle winding stays outward).
 */
object StlExporter {
    fun writeBinary(mesh: SolidMesh, out: OutputStream, name: String = "ToyForge") {
        val tc = mesh.triangleCount
        val buf = ByteBuffer.allocate(84 + 50 * tc).order(ByteOrder.LITTLE_ENDIAN)
        val header = ("ToyForge STL: $name").toByteArray(Charsets.US_ASCII).copyOf(80)
        buf.put(header)
        buf.putInt(tc)
        val v = mesh.vertices
        val p = FloatArray(9)
        for (t in 0 until tc) {
            for (k in 0 until 3) {
                val i = mesh.triangles[t * 3 + k] * 3
                p[k * 3] = v[i]; p[k * 3 + 1] = -v[i + 2]; p[k * 3 + 2] = v[i + 1]
            }
            val ux = p[3] - p[0]; val uy = p[4] - p[1]; val uz = p[5] - p[2]
            val wx = p[6] - p[0]; val wy = p[7] - p[1]; val wz = p[8] - p[2]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 0f) { nx /= l; ny /= l; nz /= l }
            buf.putFloat(nx); buf.putFloat(ny); buf.putFloat(nz)
            for (f in p) buf.putFloat(f)
            buf.putShort(0)
        }
        out.write(buf.array())
    }

    fun toBytes(mesh: SolidMesh, name: String = "ToyForge"): ByteArray =
        ByteArrayOutputStream().also { writeBinary(mesh, it, name) }.toByteArray()
}

object ThreeMfExporter {
    fun write(mesh: SolidMesh, out: OutputStream, name: String = "ToyForge") {
        val colors = mesh.triangleColors.distinct()
        val colorIndex = colors.withIndex().associate { it.value to it.index }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">""").append('\n')
        sb.append("""<metadata name="Title">""").append(escape(name)).append("</metadata>\n")
        sb.append("""<metadata name="Application">ToyForge</metadata>""").append('\n')
        sb.append("<resources>\n<basematerials id=\"1\">\n")
        for (c in colors) sb.append("<base name=\"c").append(colorIndex[c]).append("\" displaycolor=\"").append(hex(c)).append("\"/>\n")
        sb.append("</basematerials>\n<object id=\"2\" type=\"model\" pid=\"1\" pindex=\"0\">\n<mesh>\n<vertices>\n")
        val v = mesh.vertices
        for (i in 0 until mesh.vertexCount) {
            sb.append("<vertex x=\"").append(f(v[i * 3])).append("\" y=\"").append(f(-v[i * 3 + 2]))
                .append("\" z=\"").append(f(v[i * 3 + 1])).append("\"/>\n")
        }
        sb.append("</vertices>\n<triangles>\n")
        for (t in 0 until mesh.triangleCount) {
            sb.append("<triangle v1=\"").append(mesh.triangles[t * 3]).append("\" v2=\"").append(mesh.triangles[t * 3 + 1])
                .append("\" v3=\"").append(mesh.triangles[t * 3 + 2]).append("\" pid=\"1\" p1=\"")
                .append(colorIndex[mesh.triangleColors[t]]).append("\"/>\n")
        }
        sb.append("</triangles>\n</mesh>\n</object>\n</resources>\n<build>\n<item objectid=\"2\"/>\n</build>\n</model>\n")

        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                ("""<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
</Types>""").toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                ("""<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>""").toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(sb.toString().toByteArray())
            zip.closeEntry()
        }
    }

    private fun f(x: Float) = String.format(Locale.US, "%.4f", x)
    private fun hex(c: Int) = String.format(Locale.US, "#%02X%02X%02XFF", (c shr 16) and 255, (c shr 8) and 255, c and 255)
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
