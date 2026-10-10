package com.kidtoy.io

import com.kidtoy.geometry.Mesh
import com.kidtoy.model.ImportedMeshRef
import com.kidtoy.model.Project
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

object ProjectJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    fun encode(p: Project): String = json.encodeToString(Project.serializer(), p)
    fun decode(s: String): Project = json.decodeFromString(Project.serializer(), s)

    fun meshToRef(id: String, name: String, m: Mesh): ImportedMeshRef {
        val bb = ByteBuffer.allocate(m.positions.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in m.positions) bb.putFloat(f)
        return ImportedMeshRef(id, name, Base64.getEncoder().encodeToString(bb.array()))
    }

    fun refToMesh(r: ImportedMeshRef): Mesh {
        val bytes = Base64.getDecoder().decode(r.base64Triangles)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val f = FloatArray(bytes.size / 4) { bb.float }
        return Mesh.flat(f.copyOf(f.size / 9 * 9))
    }
}

/** Projects live as JSON files in the app's private storage. */
class ProjectStore(private val dir: File) {
    init { dir.mkdirs() }

    data class Entry(val file: File, val name: String, val savedAt: Long, val parts: Int)

    fun save(p: Project, fileName: String): File {
        val f = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeText(ProjectJson.encode(p))
        tmp.renameTo(f)
        return f
    }

    fun load(f: File): Project = ProjectJson.decode(f.readText())

    fun list(): List<Entry> = (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != AUTOSAVE } ?: emptyArray())
        .mapNotNull { f -> runCatching { val p = load(f); Entry(f, p.name, p.savedAt, p.shapes.size) }.getOrNull() }
        .sortedByDescending { it.savedAt }

    fun delete(f: File) = f.delete()

    fun autosaveFile() = File(dir, AUTOSAVE)

    fun thumbDir(): File = File(dir, "thumbs").apply { mkdirs() }

    companion object { const val AUTOSAVE = "autosave.json" }
}
