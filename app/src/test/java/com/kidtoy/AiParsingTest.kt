package com.kidtoy

import com.kidtoy.ai.AiException
import com.kidtoy.ai.AiParseException
import com.kidtoy.ai.AiShapeParser
import com.kidtoy.ai.AnthropicToyDesigner
import com.kidtoy.ai.HttpTransport
import com.kidtoy.ai.Templates
import com.kidtoy.model.ShapeType
import com.kidtoy.model.Vec3
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiParsingTest {
    @Test fun parsesStrictJson() {
        val d = AiShapeParser.parse(
            """{"name":"Dragon Car","message":"Rawr!","shapes":[
               {"type":"cube","position":[0,10,0],"size":[40,20,30],"rotation":[0,45,0],"color":"#FF5A5F"},
               {"type":"cone","position":[0,30,0],"size":[10,20,10],"color":"#00f"}]}"""
        )
        assertEquals("Dragon Car", d.name); assertEquals("Rawr!", d.message)
        assertEquals(2, d.shapes.size)
        assertEquals(ShapeType.CUBE, d.shapes[0].type)
        assertEquals(Vec3(40f, 20f, 30f), d.shapes[0].scale)
        assertEquals(45f, d.shapes[0].rotation.y, 0f)
        assertEquals(0xFFFF5A5F.toInt(), d.shapes[0].color)
        assertEquals(0xFF0000FF.toInt(), d.shapes[1].color)
    }

    @Test fun toleratesFencesProseAliasesAndBareArrays() {
        val d = AiShapeParser.parse(
            "Sure! Here you go:\n```json\n{\"shapes\":[{\"shape\":\"ball\",\"center\":{\"x\":1,\"y\":2,\"z\":3},\"scale\":15,\"colour\":\"green\"}]}\n```\nHave fun!"
        )
        val s = d.shapes.single()
        assertEquals(ShapeType.SPHERE, s.type)
        assertEquals(Vec3(1f, 2f, 3f), s.position)
        assertEquals(Vec3(15f, 15f, 15f), s.scale)
        val arr = AiShapeParser.parse("""[{"type":"wheel","position":[0,0,0],"size":[10,5,10]}]""")
        assertEquals(ShapeType.CYLINDER, arr.shapes.single().type)
    }

    @Test fun clampsAndDropsBadShapes() {
        val d = AiShapeParser.parse(
            """{"shapes":[
              {"type":"laser-gun","position":[0,0,0],"size":[10,10,10]},
              {"type":"cube","position":[9999,-500,-9999],"size":[0.01,-5000,"x"],"rotation":[720,0,0],"color":"not a colour"}]}"""
        )
        val s = d.shapes.single()
        assertEquals(Vec3(100f, -50f, -100f), s.position)
        assertEquals(AiShapeParser.MIN_SIZE, s.scale.x, 0f)
        assertEquals(AiShapeParser.MAX_SIZE, s.scale.y, 0f)
        assertEquals(0f, s.rotation.x, 0f)
    }

    @Test fun limitsShapeCount() {
        val many = (1..100).joinToString(",") { """{"type":"cube","position":[0,0,0],"size":[5,5,5]}""" }
        assertEquals(AiShapeParser.MAX_SHAPES, AiShapeParser.parse("""{"shapes":[$many]}""").shapes.size)
    }

    @Test fun rejectsGarbage() {
        for (bad in listOf("no json here", """{"shapes":[]}""", """{"shapes":[{"type":"gun"}]}""", "{oops")) {
            try { AiShapeParser.parse(bad); fail("should fail: $bad") } catch (_: AiParseException) {}
        }
    }

    @Test fun sceneJsonRoundTrips() {
        val d = AiShapeParser.parse(Templates.all[0].json)
        val again = AiShapeParser.parse(AiShapeParser.toJson(d.name, d.shapes))
        assertEquals(d.shapes.map { it.type }, again.shapes.map { it.type })
        assertEquals(d.shapes.map { it.color }, again.shapes.map { it.color })
    }

    @Test fun allTemplatesParse() {
        for (t in Templates.all) assertTrue(t.key, AiShapeParser.parse(t.json).shapes.size >= 3)
        assertEquals("rocket", Templates.match("a rocket to the moon").key)
    }

    @Test fun anthropicClientSendsCorrectRequestAndParsesReply() = runBlocking {
        var seenUrl = ""; var seenHeaders = emptyMap<String, String>(); var seenBody = ""
        val reply = """{"id":"msg_1","type":"message","role":"assistant","content":[
            {"type":"text","text":"{\"name\":\"Dragon Car\",\"message\":\"Rawr!\",\"shapes\":[{\"type\":\"cube\",\"position\":[0,10,0],\"size\":[40,20,30],\"color\":\"#FF0000\"}]}"}],
            "stop_reason":"end_turn"}"""
        val ai = AnthropicToyDesigner("sk-test", "claude-sonnet-5-5", transport = HttpTransport { u, h, b ->
            seenUrl = u; seenHeaders = h; seenBody = b; 200 to reply
        })
        val d = ai.design("a dragon car")
        assertEquals("https://api.anthropic.com/v1/messages", seenUrl)
        assertEquals("sk-test", seenHeaders["x-api-key"])
        assertEquals("2023-06-01", seenHeaders["anthropic-version"])
        val req = Json.parseToJsonElement(seenBody).jsonObject
        assertEquals("claude-sonnet-5-5", req["model"]!!.jsonPrimitive.content)
        assertTrue(req["system"]!!.jsonPrimitive.content.contains("SAFETY RULES"))
        val msg = req["messages"]!!.jsonArray[0] as JsonObject
        assertTrue(msg["content"]!!.jsonPrimitive.content.contains("dragon car"))
        assertEquals("Dragon Car", d.name)
        assertEquals(1, d.shapes.size)
    }

    @Test fun anthropicErrorsBecomeKidFriendly() = runBlocking {
        val ai = AnthropicToyDesigner("bad", "m", transport = HttpTransport { _, _, _ -> 401 to """{"type":"error"}""" })
        try { ai.design("x"); fail() } catch (e: AiException) { assertTrue(e.kidMessage.contains("grown-up")) }
        val ai2 = AnthropicToyDesigner("k", "m", transport = HttpTransport { _, _, _ -> 200 to """{"content":[{"type":"text","text":"I can't"}]}""" })
        try { ai2.design("x"); fail() } catch (e: AiException) { assertTrue(e.message!!.startsWith("parse")) }
    }

    @Test fun mockTransportDrivesTheRealClientEndToEnd() = runBlocking {
        val mock = com.kidtoy.ai.MockAnthropicTransport(delayMs = 0)
        val ai = AnthropicToyDesigner("k", "claude-sonnet-5-5", transport = mock)
        val d = ai.design("a dragon car")
        assertEquals("Dragon Racer", d.name)
        assertEquals(15, d.shapes.size)
        val better = ai.improve(d.name, d.shapes, "wings please")
        assertEquals(19, better.shapes.size)
        assertEquals(2, mock.calls)
        // the toy must be printable once placed
        val placed = com.kidtoy.model.SceneOps.placeGroup(better.shapes, { com.kidtoy.geometry.Primitives.get(it.type) })
        assertTrue(com.kidtoy.model.SceneOps.check(placed, { com.kidtoy.geometry.Primitives.get(it.type) }).ok)
    }
}
