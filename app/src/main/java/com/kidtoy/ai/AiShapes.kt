package com.kidtoy.ai

import com.kidtoy.model.Palette
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.model.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID

data class AiDesign(val name: String, val message: String, val shapes: List<Shape>)

class AiParseException(msg: String) : Exception(msg)

/**
 * Tolerant parser for the JSON the model returns. It accepts code fences, prose around the JSON,
 * a bare array instead of an object, and a few key aliases; it validates and clamps every value so
 * nothing the model says can put a shape off the plate or make it absurdly large or small.
 */
object AiShapeParser {
    const val MAX_SHAPES = 40
    const val MIN_SIZE = 2f
    const val MAX_SIZE = 180f
    const val PLATE_HALF = 100f

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String, idGen: () -> String = { UUID.randomUUID().toString() }): AiDesign {
        val root = extractJson(text) ?: throw AiParseException("No JSON found in reply")
        val obj = root as? JsonObject
        val arr = when {
            root is JsonArray -> root
            obj != null -> (obj["shapes"] ?: obj["parts"] ?: obj["primitives"]) as? JsonArray
            else -> null
        } ?: throw AiParseException("Reply has no shapes list")
        val shapes = arr.take(MAX_SHAPES).mapNotNull { (it as? JsonObject)?.let { o -> parseShape(o, idGen) } }
        if (shapes.isEmpty()) throw AiParseException("Reply had no usable shapes")
        val name = obj?.str("name")?.take(40) ?: "My Toy"
        val msg = obj?.let { it.str("message") ?: it.str("say") }?.take(200) ?: ""
        return AiDesign(name, msg, shapes)
    }

    fun extractJson(text: String): JsonElement? {
        val cleaned = text.replace(Regex("```(json)?", RegexOption.IGNORE_CASE), "").trim()
        runCatching { return json.parseToJsonElement(cleaned) }
        val starts = listOf(cleaned.indexOf('{'), cleaned.indexOf('[')).filter { it >= 0 }
        for (s in starts.sorted()) {
            val close = if (cleaned[s] == '{') '}' else ']'
            var e = cleaned.lastIndexOf(close)
            while (e > s) {
                val r = runCatching { json.parseToJsonElement(cleaned.substring(s, e + 1)) }.getOrNull()
                if (r != null) return r
                e = cleaned.lastIndexOf(close, e - 1)
            }
        }
        return null
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    fun typeOf(s: String?): ShapeType? = when (s?.lowercase(Locale.US)?.trim()) {
        "cube", "box", "block", "cuboid", "rectangle" -> ShapeType.CUBE
        "sphere", "ball", "ellipsoid" -> ShapeType.SPHERE
        "cylinder", "tube", "disc", "disk", "wheel" -> ShapeType.CYLINDER
        "cone" -> ShapeType.CONE
        "torus", "donut", "ring" -> ShapeType.TORUS
        "star" -> ShapeType.STAR
        "heart" -> ShapeType.HEART
        "pyramid" -> ShapeType.PYRAMID
        "text", "letters", "name", "word" -> ShapeType.TEXT
        else -> null
    }

    private fun vec(e: JsonElement?, def: Vec3): Vec3 = when (e) {
        is JsonArray -> {
            val f = e.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
            when (f.size) { 0 -> def; 1 -> Vec3(f[0], f[0], f[0]); 2 -> Vec3(f[0], f[1], def.z); else -> Vec3(f[0], f[1], f[2]) }
        }
        is JsonObject -> Vec3(
            (e["x"] as? JsonPrimitive)?.floatOrNull ?: def.x,
            (e["y"] as? JsonPrimitive)?.floatOrNull ?: def.y,
            (e["z"] as? JsonPrimitive)?.floatOrNull ?: def.z,
        )
        is JsonPrimitive -> e.floatOrNull?.let { Vec3(it, it, it) } ?: def
        else -> def
    }

    private fun finite(v: Vec3, def: Vec3) = if (v.x.isFinite() && v.y.isFinite() && v.z.isFinite()) v else def

    private fun parseShape(o: JsonObject, idGen: () -> String): Shape? {
        val type = typeOf(o.str("type") ?: o.str("shape")) ?: return null
        val size = finite(vec(o["size"] ?: o["scale"] ?: o["dimensions"], Vec3(20f, 20f, 20f)), Vec3(20f, 20f, 20f))
        val pos = finite(vec(o["position"] ?: o["pos"] ?: o["center"], Vec3()), Vec3())
        val rot = finite(vec(o["rotation"] ?: o["rot"], Vec3()), Vec3())
        fun c(v: Float) = v.coerceIn(MIN_SIZE, MAX_SIZE)
        val text = if (type == ShapeType.TEXT) (o.str("text") ?: o.str("word") ?: return null).take(com.kidtoy.text.Text3D.MAX_CHARS) else null
        if (text != null && text.isBlank()) return null
        return Shape(
            id = idGen(),
            type = type,
            position = Vec3(pos.x.coerceIn(-PLATE_HALF, PLATE_HALF), pos.y.coerceIn(-50f, 200f), pos.z.coerceIn(-PLATE_HALF, PLATE_HALF)),
            scale = Vec3(c(kotlin.math.abs(size.x)), c(kotlin.math.abs(size.y)), c(kotlin.math.abs(size.z))),
            rotation = Vec3(rot.x % 360f, rot.y % 360f, rot.z % 360f),
            color = parseColor(o.str("color") ?: o.str("colour")),
            text = text,
        )
    }

    private val named = mapOf(
        "red" to Palette.colors[0], "orange" to Palette.colors[1], "yellow" to Palette.colors[2], "green" to Palette.colors[3],
        "lightblue" to Palette.colors[4], "sky" to Palette.colors[4], "cyan" to Palette.colors[4], "blue" to Palette.colors[5],
        "purple" to Palette.colors[6], "violet" to Palette.colors[6], "pink" to Palette.colors[7], "brown" to Palette.colors[8],
        "white" to Palette.colors[9], "black" to Palette.colors[10], "gray" to 0xFF9E9E9E.toInt(), "grey" to 0xFF9E9E9E.toInt(),
    )

    fun parseColor(s: String?): Int {
        if (s == null) return Palette.colors[4]
        val t = s.trim().lowercase(Locale.US)
        named[t.replace(" ", "")]?.let { return it }
        val hex = t.removePrefix("#")
        return when {
            Regex("[0-9a-f]{6}").matches(hex) -> (0xFF000000 or hex.toLong(16)).toInt()
            Regex("[0-9a-f]{3}").matches(hex) -> {
                val r = hex[0].digitToInt(16) * 17; val g = hex[1].digitToInt(16) * 17; val b = hex[2].digitToInt(16) * 17
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> Palette.colors[4]
        }
    }

    fun colorHex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

    /** Scene -> the same compact JSON format, used for "make it better". */
    fun toJson(name: String, shapes: List<Shape>): String = buildJsonObject {
        put("name", name)
        put("shapes", buildJsonArray {
            for (s in shapes.filter { it.type != ShapeType.MESH }) add(buildJsonObject {
                put("type", s.type.name.lowercase(Locale.US))
                put("position", arr(s.position)); put("size", arr(s.scale)); put("rotation", arr(s.rotation))
                put("color", colorHex(s.color))
                if (s.type == ShapeType.TEXT) put("text", s.text ?: "")
            })
        })
    }.toString()

    private fun r1(f: Float) = Math.round(f * 10f) / 10f
    private fun arr(v: Vec3) = buildJsonArray { add(JsonPrimitive(r1(v.x))); add(JsonPrimitive(r1(v.y))); add(JsonPrimitive(r1(v.z))) }
}
