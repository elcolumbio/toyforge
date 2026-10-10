package com.kidtoy.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Fake Messages API for demos / emulator tests (enable with AI_MOCK=true in local.properties).
 * Returns a realistic Messages API envelope so the real client + parser path is exercised.
 */
class MockAnthropicTransport(private val delayMs: Long = 1500) : HttpTransport {
    var calls = 0; private set

    override fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        calls++
        Thread.sleep(delayMs)
        val req = Json.parseToJsonElement(body).jsonObject
        val user = req["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        val text = when {
            user.contains("current toy") -> IMPROVED
            user.contains("alien", ignoreCase = true) -> ALIEN
            else -> DRAGON_CAR
        }
        val envelope = buildJsonObject {
            put("id", "msg_mock_$calls"); put("type", "message"); put("role", "assistant")
            put("model", req["model"]!!.jsonPrimitive.content)
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
            put("stop_reason", "end_turn")
        }
        return 200 to envelope.toString()
    }

    companion object {
        /** An original big-eared blue alien built only from primitives (no third-party character assets). */
        val ALIEN = """{"name":"Blue Alien Buddy","message":"Meet your blue alien buddy! Those big ears can hear a cookie from far away!","shapes":[
{"type":"cylinder","position":[-10,8,0],"size":[13,16,13],"color":"#5B6CFF"},
{"type":"cylinder","position":[10,8,0],"size":[13,16,13],"color":"#5B6CFF"},
{"type":"sphere","position":[-10,4,4],"size":[16,8,20],"color":"#5B6CFF"},
{"type":"sphere","position":[10,4,4],"size":[16,8,20],"color":"#5B6CFF"},
{"type":"sphere","position":[0,30,0],"size":[38,40,32],"color":"#5B6CFF"},
{"type":"sphere","position":[0,28,9],"size":[24,28,16],"color":"#2EC4F1"},
{"type":"cylinder","position":[-21,33,0],"size":[9,24,9],"rotation":[0,0,-50],"color":"#5B6CFF"},
{"type":"cylinder","position":[21,33,0],"size":[9,24,9],"rotation":[0,0,50],"color":"#5B6CFF"},
{"type":"sphere","position":[-30,26,0],"size":[11,11,11],"color":"#5B6CFF"},
{"type":"sphere","position":[30,26,0],"size":[11,11,11],"color":"#5B6CFF"},
{"type":"sphere","position":[0,62,2],"size":[50,40,40],"color":"#5B6CFF"},
{"type":"sphere","position":[-36,70,-2],"size":[36,16,6],"rotation":[0,0,-20],"color":"#5B6CFF"},
{"type":"sphere","position":[36,70,-2],"size":[36,16,6],"rotation":[0,0,20],"color":"#5B6CFF"},
{"type":"sphere","position":[-37,70,1],"size":[24,9,4],"rotation":[0,0,-20],"color":"#FF7EC7"},
{"type":"sphere","position":[37,70,1],"size":[24,9,4],"rotation":[0,0,20],"color":"#FF7EC7"},
{"type":"sphere","position":[-10,66,18],"size":[14,16,8],"color":"#37474F"},
{"type":"sphere","position":[10,66,18],"size":[14,16,8],"color":"#37474F"},
{"type":"sphere","position":[-8,69,21.5],"size":[4,4,3],"color":"#FFFFFF"},
{"type":"sphere","position":[12,69,21.5],"size":[4,4,3],"color":"#FFFFFF"},
{"type":"sphere","position":[0,57,21],"size":[14,9,8],"color":"#37474F"}]}"""

        private const val BASE = """
{"type":"cube","position":[0,17,0],"size":[60,18,30],"color":"#3DDC97"},
{"type":"sphere","position":[34,32,0],"size":[26,24,24],"color":"#3DDC97"},
{"type":"cube","position":[47,28,0],"size":[14,10,16],"color":"#3DDC97"},
{"type":"sphere","position":[42,38,7],"size":[7,7,7],"color":"#FFFFFF"},
{"type":"sphere","position":[42,38,-7],"size":[7,7,7],"color":"#FFFFFF"},
{"type":"cone","position":[30,47,6],"size":[6,12,6],"color":"#FFD23F"},
{"type":"cone","position":[30,47,-6],"size":[6,12,6],"color":"#FFD23F"},
{"type":"cone","position":[-18,30,0],"size":[9,12,9],"color":"#FF9F1C"},
{"type":"cone","position":[-6,30,0],"size":[9,12,9],"color":"#FF9F1C"},
{"type":"cone","position":[6,30,0],"size":[9,12,9],"color":"#FF9F1C"},
{"type":"cone","position":[-38,18,0],"size":[12,30,12],"rotation":[0,0,90],"color":"#3DDC97"},
{"type":"cylinder","position":[20,9,15],"size":[18,10,18],"rotation":[90,0,0],"color":"#5B6CFF"},
{"type":"cylinder","position":[-20,9,15],"size":[18,10,18],"rotation":[90,0,0],"color":"#5B6CFF"},
{"type":"cylinder","position":[20,9,-15],"size":[18,10,18],"rotation":[90,0,0],"color":"#5B6CFF"},
{"type":"cylinder","position":[-20,9,-15],"size":[18,10,18],"rotation":[90,0,0],"color":"#5B6CFF"}"""
        val DRAGON_CAR = """{"name":"Dragon Racer","message":"Roar! Your dragon car is ready to zoom!","shapes":[$BASE]}"""
        val IMPROVED = """{"name":"Dragon Racer","message":"I added wings and a fiery star! Whoosh!","shapes":[$BASE,
{"type":"cube","position":[0,29,22],"size":[26,4,22],"rotation":[-20,0,0],"color":"#B15CFF"},
{"type":"cube","position":[0,29,-22],"size":[26,4,22],"rotation":[20,0,0],"color":"#B15CFF"},
{"type":"star","position":[0,17,16],"size":[14,14,4],"color":"#FFD23F"},
{"type":"star","position":[0,17,-16],"size":[14,14,4],"color":"#FFD23F"}]}"""
    }
}
