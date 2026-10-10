package com.kidtoy.io

import com.kidtoy.geometry.SolidMesh
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

/** Kid-proof print recipe. Everything is a string because that is how Bambu Studio / OrcaSlicer store options. */
data class PrintPlan(
    val layerHeight: Float = 0.2f,
    val wallLoops: Int = 3,
    val infillPercent: Int = 15,
    val infillPattern: String = "gyroid",
    val supports: Boolean = false,
    val brim: Boolean = false,
    val overhangAreaMm2: Double = 0.0,
    val footprintMm2: Double = 0.0,
    val heightMm: Double = 0.0,
) {
    /** Per-object overrides written into Metadata/model_settings.config (they sit on top of the user's own printer + filament). */
    fun objectSettings(): List<Pair<String, String>> = buildList {
        add("layer_height" to String.format(Locale.US, "%.2f", layerHeight))
        add("wall_loops" to wallLoops.toString())
        add("sparse_infill_density" to "$infillPercent%")
        add("sparse_infill_pattern" to infillPattern)
        add("enable_support" to if (supports) "1" else "0")
        if (supports) add("support_type" to "tree(auto)")
        if (brim) { add("brim_type" to "outer_only"); add("brim_width" to "5") }
    }

    /** Short, friendly lines for the print dialog. */
    fun kidSummary(): List<String> = buildList {
        add("Strong walls, light bouncy inside")
        add(if (supports) "Tree supports for the bits that hang in the air" else "No supports needed")
        if (brim) add("A brim so it sticks to the plate")
    }
}

/** Looks at the final solid (Z-up print coordinates) and decides supports and brim. */
object PrintAdvisor {
    /** A face counts as overhang when it points down more steeply than 45 degrees and is not resting on the plate. */
    /**
     * [hidden] (in the mesh's own coordinates) marks points that are buried inside another part; downward faces there
     * are not real overhangs. Used for the instant estimate on un-fused shells.
     */
    fun analyse(mesh: SolidMesh, zUp: Boolean = true, hidden: ((Float, Float, Float) -> Boolean)? = null): PrintPlan {
        val v = mesh.vertices
        fun z(i: Int) = if (zUp) v[i * 3 + 2] else v[i * 3 + 1]
        fun x(i: Int) = v[i * 3]
        fun y(i: Int) = if (zUp) v[i * 3 + 1] else -v[i * 3 + 2]
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until mesh.vertexCount) { minZ = minOf(minZ, z(i)); maxZ = maxOf(maxZ, z(i)) }
        var overhang = 0.0; var footprint = 0.0
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.triangles[t * 3]; val b = mesh.triangles[t * 3 + 1]; val c = mesh.triangles[t * 3 + 2]
            val ux = x(b) - x(a); val uy = y(b) - y(a); val uz = z(b) - z(a)
            val wx = x(c) - x(a); val wy = y(c) - y(a); val wz = z(c) - z(a)
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            val len = sqrt((nx * nx + ny * ny + nz * nz).toDouble())
            if (len < 1e-12) continue
            val area = len / 2
            val cosDown = -nz / len
            val lowest = minOf(z(a), z(b), z(c)) - minZ
            if (cosDown > 0.99 && maxOf(z(a), z(b), z(c)) - minZ < 0.3) footprint += area
            else if (cosDown > 0.7071 && lowest > 0.6) {
                if (hidden != null) {
                    val ia = a * 3; val ib = b * 3; val ic = c * 3
                    val cx = (v[ia] + v[ib] + v[ic]) / 3; val cy = (v[ia + 1] + v[ib + 1] + v[ic + 1]) / 3; val cz = (v[ia + 2] + v[ib + 2] + v[ic + 2]) / 3
                    if (hidden(cx, cy, cz)) continue
                }
                overhang += area
            }
        }
        val height = (maxZ - minZ).toDouble()
        val supports = overhang > 25.0
        val brim = footprint < 450.0 || (footprint > 0 && height / sqrt(footprint) > 2.5)
        return PrintPlan(supports = supports, brim = brim, overhangAreaMm2 = overhang, footprintMm2 = footprint, heightMm = height)
    }
}

/**
 * Writes a Bambu Studio / OrcaSlicer flavoured 3MF project:
 *  - one object made of one closed part per colour (part k -> filament slot k, so AMS users get colours),
 *  - the object placed in the middle of the chosen bed, resting on it,
 *  - the [PrintPlan] as object-level settings (layer height, walls, infill, supports, brim),
 *  - an optional plate thumbnail.
 * No printer/filament profile is embedded on purpose: the slicer keeps the user's own printer and filament,
 * which avoids "wrong printer" mistakes. Format follows bbs_3mf.cpp (production extension + the Metadata config files).
 */
object BambuProjectExporter {
    class Part(val name: String, val color: Int, val mesh: SolidMesh)

    private const val OBJ_UUID = "-61cb-4c03-9d28-80fed5dfa1dc"
    private const val SUB_UUID = "-81cb-4c03-9d28-80fed5dfa1dc"
    private const val COMP_UUID = "-b206-40ff-9872-83e8017abed1"
    private const val ITEM_UUID = "-b1ec-4553-aec9-835e5b724bb4"
    private const val BUILD_UUID = "2c7c17d8-22b5-4d84-8835-1976022ea369"

    fun toBytes(parts: List<Part>, plan: PrintPlan, name: String, bedSize: Float = 256f, thumbnailPng: ByteArray? = null): ByteArray =
        ByteArrayOutputStream().also { write(parts, plan, name, it, bedSize, thumbnailPng) }.toByteArray()

    fun write(parts: List<Part>, plan: PrintPlan, name: String, out: OutputStream, bedSize: Float = 256f, thumbnailPng: ByteArray? = null) {
        require(parts.isNotEmpty())
        // Print coordinates: (x, y, z)_app -> (x, -z, y). Bounds over all parts.
        val mn = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        val mx = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (p in parts) {
            val v = p.mesh.vertices
            for (i in 0 until p.mesh.vertexCount) {
                val q = doubleArrayOf(v[i * 3].toDouble(), -v[i * 3 + 2].toDouble(), v[i * 3 + 1].toDouble())
                for (a in 0 until 3) { mn[a] = minOf(mn[a], q[a]); mx[a] = maxOf(mx[a], q[a]) }
            }
        }
        val c = DoubleArray(3) { (mn[it] + mx[it]) / 2 }
        val tx = bedSize / 2.0; val ty = bedSize / 2.0; val tz = c[2] - mn[2]

        val objectId = parts.size * 2 // sub-model objects use odd ids 1,3,5..; the assembly gets an even id
        fun subId(k: Int) = 2 * k + 1
        val sub = StringBuilder()
        sub.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sub.append("""<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:BambuStudio="http://schemas.bambulab.com/package/2021" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06" requiredextensions="p">""").append('\n')
        sub.append(""" <metadata name="BambuStudio:3mfVersion">1</metadata>""").append("\n <resources>\n")
        parts.forEachIndexed { k, p ->
            sub.append("  <object id=\"").append(subId(k)).append("\" p:UUID=\"").append(uuid(k + 1, SUB_UUID)).append("\" type=\"model\">\n   <mesh>\n    <vertices>\n")
            val v = p.mesh.vertices
            for (i in 0 until p.mesh.vertexCount) {
                sub.append("     <vertex x=\"").append(f(v[i * 3] - c[0])).append("\" y=\"").append(f(-v[i * 3 + 2] - c[1]))
                    .append("\" z=\"").append(f(v[i * 3 + 1] - c[2])).append("\"/>\n")
            }
            sub.append("    </vertices>\n    <triangles>\n")
            val t = p.mesh.triangles
            for (i in 0 until p.mesh.triangleCount) {
                sub.append("     <triangle v1=\"").append(t[i * 3]).append("\" v2=\"").append(t[i * 3 + 1]).append("\" v3=\"").append(t[i * 3 + 2]).append("\"/>\n")
            }
            sub.append("    </triangles>\n   </mesh>\n  </object>\n")
        }
        sub.append(" </resources>\n <build/>\n</model>\n")

        val root = StringBuilder()
        root.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        root.append("""<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:BambuStudio="http://schemas.bambulab.com/package/2021" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06" requiredextensions="p">""").append('\n')
        root.append(" <metadata name=\"Application\">ToyForge-1.0</metadata>\n")
        root.append(" <metadata name=\"BambuStudio:3mfVersion\">1</metadata>\n")
        root.append(" <metadata name=\"Title\">").append(esc(name)).append("</metadata>\n")
        root.append(" <metadata name=\"Designer\">ToyForge kid</metadata>\n")
        root.append(" <resources>\n  <object id=\"").append(objectId).append("\" p:UUID=\"").append(uuid(1, OBJ_UUID)).append("\" type=\"model\">\n   <components>\n")
        parts.forEachIndexed { k, _ ->
            root.append("    <component p:path=\"/3D/Objects/object_1.model\" objectid=\"").append(subId(k)).append("\" p:UUID=\"")
                .append(uuid(k + 1 + (1 shl 16), COMP_UUID)).append("\" transform=\"1 0 0 0 1 0 0 0 1 0 0 0\"/>\n")
        }
        root.append("   </components>\n  </object>\n </resources>\n")
        root.append(" <build p:UUID=\"").append(BUILD_UUID).append("\">\n")
        root.append("  <item objectid=\"").append(objectId).append("\" p:UUID=\"").append(uuid(2, ITEM_UUID))
            .append("\" transform=\"1 0 0 0 1 0 0 0 1 ").append(f(tx)).append(' ').append(f(ty)).append(' ').append(f(tz)).append("\" printable=\"1\"/>\n")
        root.append(" </build>\n</model>\n")

        val settings = StringBuilder()
        settings.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n<config>\n")
        settings.append("  <object id=\"").append(objectId).append("\">\n")
        settings.append("    <metadata key=\"name\" value=\"").append(esc(name)).append("\"/>\n")
        settings.append("    <metadata key=\"extruder\" value=\"1\"/>\n")
        for ((k, v) in plan.objectSettings()) settings.append("    <metadata key=\"").append(k).append("\" value=\"").append(esc(v)).append("\"/>\n")
        parts.forEachIndexed { k, p ->
            settings.append("    <part id=\"").append(subId(k)).append("\" subtype=\"normal_part\">\n")
            settings.append("      <metadata key=\"name\" value=\"").append(esc(p.name)).append("\"/>\n")
            settings.append("      <metadata key=\"matrix\" value=\"1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1\"/>\n")
            settings.append("      <metadata key=\"extruder\" value=\"").append(k + 1).append("\"/>\n")
            settings.append("      <mesh_stat edges_fixed=\"0\" degenerate_facets=\"0\" facets_removed=\"0\" facets_reversed=\"0\" backwards_edges=\"0\"/>\n")
            settings.append("    </part>\n")
        }
        settings.append("  </object>\n  <plate>\n")
        settings.append("    <metadata key=\"plater_id\" value=\"1\"/>\n    <metadata key=\"plater_name\" value=\"\"/>\n    <metadata key=\"locked\" value=\"false\"/>\n")
        if (thumbnailPng != null) settings.append("    <metadata key=\"thumbnail_file\" value=\"Metadata/plate_1.png\"/>\n")
        settings.append("    <model_instance>\n      <metadata key=\"object_id\" value=\"").append(objectId).append("\"/>\n")
        settings.append("      <metadata key=\"instance_id\" value=\"0\"/>\n      <metadata key=\"identify_id\" value=\"").append(objectId + 100).append("\"/>\n    </model_instance>\n")
        settings.append("  </plate>\n  <assemble>\n   <assemble_item object_id=\"").append(objectId).append("\" instance_id=\"0\" transform=\"1 0 0 0 1 0 0 0 1 ")
            .append(f(tx)).append(' ').append(f(ty)).append(' ').append(f(tz)).append("\" offset=\"0 0 0\"/>\n  </assemble>\n</config>\n")

        // Filament colours as a hint for the user (colour k = slot k). Bambu reads this file only for display.
        val colours = parts.joinToString(";") { hex(it.color) }

        ZipOutputStream(out).use { zip ->
            fun put(path: String, data: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(data); zip.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
 <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
 <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
 <Default Extension="png" ContentType="image/png"/>
 <Default Extension="config" ContentType="text/xml"/>
</Types>
""".toByteArray())
            val thumbRel = if (thumbnailPng != null)
                "\n <Relationship Target=\"/Metadata/plate_1.png\" Id=\"rel-2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail\"/>" else ""
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/3dmodel.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>$thumbRel
</Relationships>
""".toByteArray())
            put("3D/3dmodel.model", root.toString().toByteArray())
            put("3D/_rels/3dmodel.model.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/Objects/object_1.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>
""".toByteArray())
            put("3D/Objects/object_1.model", sub.toString().toByteArray())
            put("Metadata/model_settings.config", settings.toString().toByteArray())
            put("Metadata/toyforge.config", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n <metadata key=\"Application\" value=\"ToyForge\"/>\n" +
                " <metadata key=\"filament_colours\" value=\"$colours\"/>\n</config>\n").toByteArray())
            if (thumbnailPng != null) put("Metadata/plate_1.png", thumbnailPng)
        }
    }

    private fun uuid(index: Int, suffix: String) = String.format(Locale.US, "%08X", index) + suffix
    private fun f(x: Double) = String.format(Locale.US, "%.4f", x)
    private fun f(x: Float) = f(x.toDouble())
    private fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
