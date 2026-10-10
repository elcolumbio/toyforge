package com.kidtoy.model

import kotlinx.serialization.Serializable

@Serializable
enum class ShapeType { CUBE, SPHERE, CYLINDER, CONE, TORUS, STAR, HEART, PYRAMID, MESH, TEXT }

@Serializable
data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun max(): Float = maxOf(x, maxOf(y, z))
    fun min(): Float = minOf(x, minOf(y, z))
}

/**
 * One building block. Units are millimetres, Y is up, the build plate is the plane y = 0.
 * The primitive mesh lives in the unit box [-0.5, 0.5]^3, so [scale] is the size in mm per axis.
 * [rotation] is in degrees, applied X, then Y, then Z (model = T * Rz * Ry * Rx * S).
 */
@Serializable
data class Shape(
    val id: String,
    val type: ShapeType,
    val position: Vec3,
    val scale: Vec3,
    val rotation: Vec3 = Vec3(),
    val color: Int,
    val meshId: String? = null,
    /** For [ShapeType.TEXT]: the word shown as raised letters. */
    val text: String? = null,
)

/** A model imported from STL/OBJ, normalised so that its largest side is 1 and it is centred at the origin. */
@Serializable
data class ImportedMeshRef(val id: String, val name: String, val base64Triangles: String)

@Serializable
data class Project(
    val version: Int = 1,
    val name: String = "My Toy",
    val shapes: List<Shape> = emptyList(),
    val meshes: List<ImportedMeshRef> = emptyList(),
    val savedAt: Long = 0L,
)

object Palette {
    val colors: List<Int> = listOf(
        0xFFFF5A5F.toInt(), // red
        0xFFFF9F1C.toInt(), // orange
        0xFFFFD23F.toInt(), // yellow
        0xFF3DDC97.toInt(), // green
        0xFF2EC4F1.toInt(), // sky
        0xFF5B6CFF.toInt(), // blue
        0xFFB15CFF.toInt(), // purple
        0xFFFF7EC7.toInt(), // pink
        0xFF8D6E63.toInt(), // brown
        0xFFFFFFFF.toInt(), // white
        0xFF37474F.toInt(), // dark
    )
}
