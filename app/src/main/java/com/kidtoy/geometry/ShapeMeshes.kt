package com.kidtoy.geometry

import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.text.Text3D

/** One place that knows which unit-box mesh belongs to a shape (primitive, imported model or 3D text). */
object ShapeMeshes {
    fun of(s: Shape, imported: Map<String, Mesh>): Mesh = when (s.type) {
        ShapeType.MESH -> imported[s.meshId] ?: Primitives.get(ShapeType.CUBE)
        ShapeType.TEXT -> s.text?.let { Text3D.build(it)?.mesh } ?: Primitives.get(ShapeType.CUBE)
        else -> Primitives.get(s.type)
    }

    /** Cache key for GPU buffers. */
    fun key(s: Shape): String = when (s.type) {
        ShapeType.MESH -> "mesh:${s.meshId}"
        ShapeType.TEXT -> "text:${s.text}"
        else -> s.type.name
    }
}
