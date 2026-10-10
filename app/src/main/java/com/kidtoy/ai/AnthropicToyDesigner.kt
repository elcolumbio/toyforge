package com.kidtoy.ai

import com.kidtoy.model.Shape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

class AiException(msg: String, val kidMessage: String) : Exception(msg)

/**
 * Calls the Anthropic Messages API. With [baseUrl] pointing at your own backend proxy and an empty
 * [apiKey], the same class works against a proxy that forwards the Messages API (recommended for release).
 */
class AnthropicToyDesigner(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val transport: HttpTransport = UrlConnectionTransport,
) : ToyDesigner {
    override val isOnline = true

    override suspend fun design(idea: String): AiDesign = ask(KidSafePrompt.designUser(idea))

    override suspend fun improve(name: String, current: List<Shape>, wish: String?, importedBounds: FloatArray?): AiDesign =
        ask(KidSafePrompt.improveUser(AiShapeParser.toJson(name, current), wish, importedBounds))

    fun buildRequest(user: String): String = buildJsonObject {
        put("model", model)
        put("max_tokens", 4096)
        put("system", KidSafePrompt.SYSTEM)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", user) })
        })
    }.toString()

    private suspend fun ask(user: String): AiDesign = withContext(Dispatchers.IO) {
        val headers = buildMap {
            put("content-type", "application/json")
            put("anthropic-version", "2023-06-01")
            if (apiKey.isNotBlank()) put("x-api-key", apiKey)
        }
        val (code, body) = try {
            transport.post("${baseUrl.trimEnd('/')}/v1/messages", headers, buildRequest(user))
        } catch (e: Exception) {
            throw AiException("network: ${e.message}", "I can't reach the internet right now.")
        }
        if (code !in 200..299) {
            val kid = when (code) {
                401, 403 -> "My magic key isn't working. Ask a grown-up!"
                429, 529 -> "Lots of kids are building! Try again in a moment."
                else -> "Oops, something went wrong. Try again!"
            }
            throw AiException("HTTP $code: ${body.take(300)}", kid)
        }
        val text = extractText(body)
        try {
            AiShapeParser.parse(text)
        } catch (e: AiParseException) {
            throw AiException("parse: ${e.message}", "Hmm, I got confused. Try saying it another way!")
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Concatenates all "text" content blocks of a Messages API response. */
        fun extractText(body: String): String {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return body
            val content = root["content"] as? JsonArray ?: return body
            return content.mapNotNull { b ->
                val o = b as? JsonObject ?: return@mapNotNull null
                if ((o["type"] as? JsonPrimitive)?.contentOrNull == "text") o["text"]?.jsonPrimitive?.contentOrNull else null
            }.joinToString("\n")
        }
    }
}

object UrlConnectionTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 120_000
        c.doOutput = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }
}
