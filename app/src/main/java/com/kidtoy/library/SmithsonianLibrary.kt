package com.kidtoy.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

fun interface HttpGet {
    /** Returns (statusCode, bytes). */
    fun get(url: String): Pair<Int, ByteArray>
}

object UrlConnectionGet : HttpGet {
    override fun get(url: String): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        val code = c.responseCode
        val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > 40_000_000) throw IllegalStateException("download too big")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: ByteArray(0)
        c.disconnect()
        return code to bytes
    }
}

class LibraryException(msg: String, val kidMessage: String) : Exception(msg)

/**
 * Smithsonian Open Access (https://api.si.edu, api.data.gov key; DEMO_KEY works with low rate limits).
 * Only CC0 records that carry a 3D package with an OBJ download are returned.
 * Model files live on a separate host (3d-api.si.edu). On 2026-10-10 that host served an expired certificate
 * (CN=ids.si.edu, notAfter 2026-10-09 23:59 GMT); no other host serves the files. We never bypass certificate
 * checks: [isAvailable] probes the file host and the app hides the museum while its TLS is broken.
 */
class SmithsonianLibrary(
    private val apiKey: String = "DEMO_KEY",
    private val http: HttpGet = UrlConnectionGet,
) : ModelLibrary {
    private val json = Json { ignoreUnknownKeys = true }
    private var probedAt = 0L
    private var probeOk = true

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (probedAt != 0L && now - probedAt < 10 * 60_000) return@withContext probeOk
        probeOk = try { http.get(FILE_HOST); true } catch (e: Exception) { !isTlsProblem(e) }
        probedAt = now
        probeOk
    }

    override suspend fun search(query: String): List<LibraryItem> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("${query.trim().ifBlank { "animal" }} 3d", "UTF-8")
        val (code, body) = try {
            http.get("https://api.si.edu/openaccess/api/v1.0/search?q=$q&rows=40&api_key=$apiKey")
        } catch (e: Exception) {
            throw LibraryException("network: ${e.message}", "I can't reach the museum right now.")
        }
        if (code !in 200..299) throw LibraryException("HTTP $code", "The museum is busy. Try again later!")
        parseSearch(String(body, Charsets.UTF_8))
    }

    fun parseSearch(body: String): List<LibraryItem> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val rows = (root["response"] as? JsonObject)?.get("rows") as? JsonArray ?: return emptyList()
        return rows.mapNotNull { row ->
            val r = row as? JsonObject ?: return@mapNotNull null
            val title = r.str("title") ?: return@mapNotNull null
            val dn = r.path("content", "descriptiveNonRepeating") as? JsonObject ?: return@mapNotNull null
            val access = (dn["metadata_usage"] as? JsonObject)?.str("access")
            if (access != "CC0") return@mapNotNull null
            val media = (dn["online_media"] as? JsonObject)?.get("media") as? JsonArray ?: return@mapNotNull null
            val pkg = media.firstOrNull { (it as? JsonObject)?.str("type")?.startsWith("3d") == true } as? JsonObject
                ?: return@mapNotNull null
            val urls = (pkg["resources"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("url") } ?: emptyList()
            // prefer a decimated OBJ (fast to download & render), never the full-resolution scan
            val obj = urls.filter { it.endsWith("obj.zip", true) && !it.contains("full", true) }
                .minByOrNull { if (it.contains("100k")) 0 else 1 } ?: return@mapNotNull null
            LibraryItem(
                id = pkg.str("id") ?: obj, title = title.take(60), emoji = "🏛️", license = "CC0 (Smithsonian Open Access)",
                author = r.str("unitCode"), thumbnailUrl = pkg.str("thumbnail"), downloadUrl = obj, format = "obj.zip",
            )
        }.distinctBy { it.id }
    }

    override suspend fun fetch(item: LibraryItem): LibraryContent = withContext(Dispatchers.IO) {
        val url = item.downloadUrl ?: throw LibraryException("no url", "This one can't be downloaded.")
        val (code, bytes) = try { http.get(url) } catch (e: Exception) {
            if (isTlsProblem(e)) { probeOk = false; probedAt = System.currentTimeMillis() }
            throw LibraryException("download: ${e.message}", "The museum's door is stuck. Try another time!")
        }
        if (code !in 200..299) throw LibraryException("HTTP $code", "The museum's door is stuck. Try another time!")
        LibraryContent.MeshFile(url.substringAfterLast('/'), bytes)
    }

    companion object {
        const val FILE_HOST = "https://3d-api.si.edu/"
        fun isTlsProblem(e: Throwable): Boolean {
            var c: Throwable? = e
            while (c != null) {
                if (c is javax.net.ssl.SSLException || c is java.security.cert.CertificateException) return true
                c = c.cause
            }
            return false
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.path(vararg keys: String): JsonElement? {
        var cur: JsonElement? = this
        for (k in keys) cur = (cur as? JsonObject)?.get(k)
        return cur
    }
}

/**
 * Poly Pizza (https://poly.pizza, CC0 / CC-BY low-poly models) needs a free API key (X-Auth-Token).
 * Not wired yet: needs the key, CC-BY attribution UI, and GLB import.
 */
class PolyPizzaLibraryStub(private val apiKey: String) : ModelLibrary {
    override suspend fun search(query: String): List<LibraryItem> = emptyList()
    override suspend fun fetch(item: LibraryItem): LibraryContent = throw UnsupportedOperationException("Poly Pizza not implemented")
}
