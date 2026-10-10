package com.kidtoy.library

import com.kidtoy.ai.AiShapeParser
import com.kidtoy.ai.Templates
import com.kidtoy.model.Shape

/** One thing a kid can start from: either editable shapes or a downloadable mesh (STL/OBJ/GLB). */
data class LibraryItem(
    val id: String,
    val title: String,
    val emoji: String,
    val license: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val downloadUrl: String? = null,
    val format: String = "shapes",
)

sealed interface LibraryContent {
    data class Shapes(val shapes: List<Shape>) : LibraryContent
    data class MeshFile(val fileName: String, val bytes: ByteArray) : LibraryContent
}

/**
 * Source of ready-made models to remix. Planned remote sources (see RESEARCH.md): Smithsonian 3D (CC0),
 * Poly Pizza (CC0 / CC-BY, attribution required). Results must be filtered for kid-safety and licence
 * before showing them, ideally on a backend.
 */
interface ModelLibrary {
    suspend fun search(query: String): List<LibraryItem>
    suspend fun fetch(item: LibraryItem): LibraryContent
    /** False when the source is known to be broken right now (the UI then hides it instead of failing on tap). */
    suspend fun isAvailable(): Boolean = true
}

/** Built-in starters, fully offline. */
class StarterLibrary : ModelLibrary {
    override suspend fun search(query: String): List<LibraryItem> = Templates.all
        .filter { query.isBlank() || it.words.any { w -> w.contains(query.lowercase()) } }
        .map { LibraryItem(it.key, AiShapeParser.parse(it.json).name, it.emoji, "CC0 (ToyForge starter)") }

    override suspend fun fetch(item: LibraryItem): LibraryContent =
        LibraryContent.Shapes(AiShapeParser.parse(Templates.all.first { it.key == item.id }.json).shapes)
}

/** Placeholder for the online library ("Remix Shelf"). Not implemented yet. */
class RemoteModelLibraryStub : ModelLibrary {
    override suspend fun search(query: String): List<LibraryItem> = emptyList()
    override suspend fun fetch(item: LibraryItem): LibraryContent = throw UnsupportedOperationException("Remote library not implemented yet")
}
