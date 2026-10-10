package com.kidtoy.model

import com.kidtoy.geometry.Mesh
import com.kidtoy.text.Text3D
import java.util.UUID

/** Builds name pieces: standing letters, or a base plate under the whole toy with the name lying on its front strip. */
object NameTag {
    const val PLATE_THICKNESS = 5f
    const val LETTER_RELIEF = 2f // 10 layers; lower walls keep the narrow i-dot and counter gaps visible from the side
    const val LETTER_HEIGHT = 16f

    fun letters(text: String, color: Int, height: Float = LETTER_HEIGHT, thickness: Float = 6f): Shape? {
        val t = Text3D.clean(text)
        val built = Text3D.build(t) ?: return null
        val w = (height * built.aspect).coerceAtMost(2 * SceneOps.PLATE_HALF - 10)
        val h = w / built.aspect
        return Shape(UUID.randomUUID().toString(), ShapeType.TEXT, Vec3(0f, h / 2, 0f), Vec3(w, h, thickness), color = color, text = t)
    }

    /**
     * Returns the new scene: existing parts lifted onto a plate, plus the plate and flat letters in front.
     * Parts sink 0.5 mm into the plate so everything fuses into one printable piece.
     */
    fun nameplate(shapes: List<Shape>, meshFor: (Shape) -> Mesh, text: String, letterColor: Int, plateColor: Int): List<Shape>? {
        val t = Text3D.clean(text)
        val built = Text3D.build(t) ?: return null
        val sink = 0.5f
        var lh = LETTER_HEIGHT
        var lw = lh * built.aspect
        val maxW = 2 * SceneOps.PLATE_HALF - 20
        if (lw > maxW) { lw = maxW; lh = lw / built.aspect }
        val margin = 7f
        val toy = if (shapes.isEmpty()) null else SceneOps.groupBounds(shapes, meshFor)
        val toyW = toy?.let { it[3] - it[0] } ?: 0f
        val toyD = toy?.let { it[5] - it[2] } ?: 0f
        val cx = toy?.let { (it[0] + it[3]) / 2 } ?: 0f
        val plateW = maxOf(toyW + 2 * margin, lw + 2 * margin)
        val strip = lh + margin * 1.5f
        val plateD = (if (toy != null) toyD + margin else margin / 2) + strip
        val back = toy?.let { it[2] - margin / 2 } ?: -plateD / 2
        val pz = back + plateD / 2
        val plate = Shape(UUID.randomUUID().toString(), ShapeType.CUBE, Vec3(cx, PLATE_THICKNESS / 2, pz),
            Vec3(plateW, PLATE_THICKNESS, plateD), color = plateColor)
        val letterZ = back + plateD - strip / 2
        val letters = Shape(UUID.randomUUID().toString(), ShapeType.TEXT,
            Vec3(cx, PLATE_THICKNESS + LETTER_RELIEF / 2 - sink, letterZ), Vec3(lw, lh, LETTER_RELIEF + sink),
            Vec3(-90f, 0f, 0f), letterColor, text = t)
        val lifted = shapes.map { it.copy(position = it.position.copy(y = it.position.y + PLATE_THICKNESS - sink)) }
        // keep the whole thing centred on the build plate
        val dx = -cx; val dz = -pz
        return (lifted + plate + letters).map { it.copy(position = Vec3(it.position.x + dx, it.position.y, it.position.z + dz)) }
    }

    const val KEYCHAIN_THICKNESS = 4f

    /**
     * A keychain: a flat tag with the name raised on top and a key ring (flat torus, 8 mm hole) at the left end.
     * Everything lies flat on the plate, so it prints in about half an hour with no supports.
     */
    fun keychain(text: String, letterColor: Int, tagColor: Int): List<Shape>? {
        val t = Text3D.clean(text)
        val built = Text3D.build(t) ?: return null
        val sink = 0.5f
        var lh = 12f
        var lw = lh * built.aspect
        if (lw > 90f) { lw = 90f; lh = lw / built.aspect }
        val margin = 6f
        val tagW = lw + 2 * margin
        val tagD = lh + 2 * margin
        val ring = 20f
        val ringX = -tagW / 2 - ring / 2 + 4f // 4 mm overlap so ring and tag fuse into one piece
        val total = tagW + ring - 4f
        val dx = -(ringX - ring / 2 + total / 2) // centre the keychain on the plate
        fun id() = UUID.randomUUID().toString()
        return listOf(
            Shape(id(), ShapeType.CUBE, Vec3(dx, KEYCHAIN_THICKNESS / 2, 0f), Vec3(tagW, KEYCHAIN_THICKNESS, tagD), color = tagColor),
            Shape(id(), ShapeType.TORUS, Vec3(ringX + dx, KEYCHAIN_THICKNESS / 2, 0f), Vec3(ring, KEYCHAIN_THICKNESS / 0.3f, ring), color = tagColor),
            Shape(id(), ShapeType.TEXT, Vec3(dx, KEYCHAIN_THICKNESS + LETTER_RELIEF / 2 - sink, 0f), Vec3(lw, lh, LETTER_RELIEF + sink),
                Vec3(-90f, 0f, 0f), letterColor, text = t),
        )
    }
}
