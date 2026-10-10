package com.kidtoy.ai

/** Hand-made starter toys in the same JSON format Claude uses (so the same parser/validator applies). */
object Templates {
    data class Template(val key: String, val emoji: String, val words: List<String>, val json: String)

    val all = listOf(
        Template("car", "🚗", listOf("car", "auto", "truck", "bus", "racer", "wagen"), """
{"name":"Zoom Car","message":"Vroom! Here is a speedy car!","shapes":[
{"type":"cube","position":[0,17,0],"size":[64,18,30],"color":"#FF5A5F"},
{"type":"cube","position":[-6,32,0],"size":[32,16,26],"color":"#2EC4F1"},
{"type":"cylinder","position":[20,9,15],"size":[18,10,18],"rotation":[90,0,0],"color":"#37474F"},
{"type":"cylinder","position":[-20,9,15],"size":[18,10,18],"rotation":[90,0,0],"color":"#37474F"},
{"type":"cylinder","position":[20,9,-15],"size":[18,10,18],"rotation":[90,0,0],"color":"#37474F"},
{"type":"cylinder","position":[-20,9,-15],"size":[18,10,18],"rotation":[90,0,0],"color":"#37474F"},
{"type":"sphere","position":[32,20,9],"size":[6,6,6],"color":"#FFD23F"},
{"type":"sphere","position":[32,20,-9],"size":[6,6,6],"color":"#FFD23F"}]}"""),
        Template("rocket", "🚀", listOf("rocket", "space", "rakete", "spaceship", "ufo"), """
{"name":"Star Rocket","message":"3, 2, 1... blast off!","shapes":[
{"type":"cube","position":[0,12,0],"size":[4,24,46],"color":"#FF9F1C"},
{"type":"cube","position":[0,12,0],"size":[46,24,4],"color":"#FF9F1C"},
{"type":"cylinder","position":[0,40,0],"size":[30,60,30],"color":"#FFFFFF"},
{"type":"cone","position":[0,83,0],"size":[30,30,30],"color":"#FF5A5F"},
{"type":"sphere","position":[0,52,13],"size":[13,13,8],"color":"#2EC4F1"},
{"type":"star","position":[0,32,14],"size":[14,14,6],"color":"#FFD23F"}]}"""),
        Template("robot", "🤖", listOf("robot", "bot", "android", "roboter"), """
{"name":"Beep Bot","message":"Beep boop! Your robot friend is ready!","shapes":[
{"type":"cube","position":[-9,10,0],"size":[12,20,14],"color":"#5B6CFF"},
{"type":"cube","position":[9,10,0],"size":[12,20,14],"color":"#5B6CFF"},
{"type":"cube","position":[0,34,0],"size":[36,30,22],"color":"#2EC4F1"},
{"type":"cylinder","position":[-21,36,0],"size":[8,26,8],"color":"#5B6CFF"},
{"type":"cylinder","position":[21,36,0],"size":[8,26,8],"color":"#5B6CFF"},
{"type":"cube","position":[0,58,0],"size":[26,22,22],"color":"#2EC4F1"},
{"type":"sphere","position":[-6,60,11],"size":[6,6,4],"color":"#FFD23F"},
{"type":"sphere","position":[6,60,11],"size":[6,6,4],"color":"#FFD23F"},
{"type":"heart","position":[0,36,11],"size":[12,12,4],"color":"#FF5A5F"},
{"type":"cylinder","position":[0,73,0],"size":[4,10,4],"color":"#37474F"},
{"type":"sphere","position":[0,79,0],"size":[8,8,8],"color":"#FF5A5F"}]}"""),
        Template("snowman", "⛄", listOf("snowman", "snow", "winter", "schneemann"), """
{"name":"Frosty","message":"Brrr! A happy snowman!","shapes":[
{"type":"cylinder","position":[0,2,0],"size":[36,4,36],"color":"#FFFFFF"},
{"type":"sphere","position":[0,20,0],"size":[40,40,40],"color":"#FFFFFF"},
{"type":"sphere","position":[0,49,0],"size":[30,30,30],"color":"#FFFFFF"},
{"type":"sphere","position":[0,71,0],"size":[22,22,22],"color":"#FFFFFF"},
{"type":"cone","position":[0,71,14],"size":[5,12,5],"rotation":[90,0,0],"color":"#FF9F1C"},
{"type":"cylinder","position":[0,82,0],"size":[24,4,24],"color":"#37474F"},
{"type":"cylinder","position":[0,89,0],"size":[16,12,16],"color":"#37474F"}]}"""),
        Template("house", "🏠", listOf("house", "home", "castle", "haus", "hut"), """
{"name":"Cozy House","message":"Welcome home!","shapes":[
{"type":"cube","position":[0,18,0],"size":[50,36,40],"color":"#FFD23F"},
{"type":"pyramid","position":[0,46,0],"size":[58,24,48],"color":"#FF5A5F"},
{"type":"cube","position":[0,10,20],"size":[12,20,4],"color":"#8D6E63"},
{"type":"cube","position":[-15,24,20],"size":[10,10,4],"color":"#2EC4F1"},
{"type":"cube","position":[15,24,20],"size":[10,10,4],"color":"#2EC4F1"},
{"type":"cube","position":[14,52,-6],"size":[8,18,8],"color":"#8D6E63"}]}"""),
        Template("cat", "🐱", listOf("cat", "kitty", "katze", "animal", "pet", "dog"), """
{"name":"Kitty","message":"Meow! A cuddly cat for you!","shapes":[
{"type":"cylinder","position":[0,3,0],"size":[46,6,32],"color":"#FF9F1C"},
{"type":"sphere","position":[0,18,0],"size":[44,30,30],"color":"#FF9F1C"},
{"type":"sphere","position":[22,36,0],"size":[28,26,26],"color":"#FF9F1C"},
{"type":"cone","position":[22,50,-7],"size":[9,12,9],"color":"#FF9F1C"},
{"type":"cone","position":[22,50,7],"size":[9,12,9],"color":"#FF9F1C"},
{"type":"sphere","position":[34,38,-5],"size":[5,5,5],"color":"#37474F"},
{"type":"sphere","position":[34,38,5],"size":[5,5,5],"color":"#37474F"},
{"type":"sphere","position":[36,33,0],"size":[5,4,5],"color":"#FF7EC7"},
{"type":"cylinder","position":[-25,28,0],"size":[7,30,7],"rotation":[0,0,30],"color":"#FF9F1C"}]}"""),
    )

    fun match(idea: String): Template {
        val t = idea.lowercase()
        return all.firstOrNull { tpl -> tpl.words.any { t.contains(it) } } ?: all[(idea.hashCode() and 0x7fffffff) % all.size]
    }
}

/** Used when there is no API key or no network: picks the closest starter toy. Clearly labelled as offline. */
class OfflineToyDesigner : ToyDesigner {
    override val isOnline = false
    override suspend fun design(idea: String): AiDesign {
        val d = AiShapeParser.parse(Templates.match(idea).json)
        return d.copy(message = "Offline magic: here's a starter ${d.name}! Change it any way you like.")
    }
    override suspend fun improve(name: String, current: List<com.kidtoy.model.Shape>, wish: String?, importedBounds: FloatArray?): AiDesign =
        throw AiException("offline", "Make-it-better needs the internet magic key.")
}
