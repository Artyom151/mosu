package com.mosu.skins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A skin listed in the osuskins.net catalog.
 */
data class SkinListItem(val id: String, val name: String) {

    val thumbnailUrl get() = "$CDN/screenshots/thumbnails/$id.webp"

    val previewUrl get() = "$CDN/screenshots/headers/$id.webp"

    val pageUrl get() = "$HOST/skin/$id"

    private companion object {
        const val HOST = OsuSkinsClient.HOST
        const val CDN = OsuSkinsClient.CDN
    }
}

enum class SkinSort(val value: String) { Popular("downloads"), Newest("date"), Views("views"), Name("name") }

/**
 * Reads the osuskins.net catalog. The site has no public API, so listings are read from the schema.org JSON-LD
 * `ItemList` embedded in every listing page. Files cannot be fetched from here: downloads are protected by a captcha
 * and have to be started by the user in a WebView.
 */
object OsuSkinsClient {

    const val HOST = "https://osuskins.net"
    const val CDN = "https://cdn.osuskins.net"

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android) mosu"

    private val idRegex = Regex("^[A-Za-z0-9]{3,16}$")

    private val jsonLdRegex = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    private val client = OkHttpClient()

    private val json = Json { ignoreUnknownKeys = true }


    /**
     * Whether [host] is allowed to be opened or downloaded from.
     */
    fun isTrustedHost(host: String?) =
        host != null && (host == "osuskins.net" || host.endsWith(".osuskins.net") || host.endsWith(".cloudflare.com"))


    suspend fun search(query: String, sort: SkinSort, page: Int): List<SkinListItem> = withContext(Dispatchers.IO) {

        val url = HOST.toHttpUrl().newBuilder()
            .apply { if (query.isNotBlank()) addQueryParameter("q", query.trim()) }
            .addQueryParameter("sortby", sort.value)
            .addQueryParameter("order", "desc")
            .addQueryParameter("p", page.toString())
            .build()

        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            parse(response.body!!.string())
        }
    }


    internal fun parse(html: String): List<SkinListItem> {

        val result = ArrayList<SkinListItem>()

        for (match in jsonLdRegex.findAll(html)) {
            val root = try {
                json.parseToJsonElement(match.groupValues[1]) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: continue

            if (root["@type"]?.jsonPrimitive?.content != "ItemList") continue

            val elements = root["itemListElement"] as? JsonArray ?: continue

            for (element in elements) {
                val item = element.jsonObject["item"]?.jsonObject ?: continue
                val name = item["name"]?.jsonPrimitive?.content ?: continue
                val id = item["url"]?.jsonPrimitive?.content?.substringAfterLast("/skin/", "") ?: continue

                if (idRegex.matches(id)) {
                    result.add(SkinListItem(id, name.trim()))
                }
            }
        }

        return result.distinctBy { it.id }
    }
}
