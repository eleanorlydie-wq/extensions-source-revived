package eu.kanade.tachiyomi.extension.id.manhwalandmom

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import java.time.format.DateTimeParseException

// The site was rebuilt on a different (Astro) stack in 2026; it is no
// longer a MangaThemesia/WordPress site, so this is now a plain HttpSource
// with its own bespoke parsing instead of extending MangaThemesia.
class ManhwaLandMom : HttpSource() {

    override val name = "ManhwaLand.mom"
    override val baseUrl = "https://05c.manhwaland.land"
    override val lang = "id"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ---------------- Popular / Latest / Search (shared card grid) ----------------

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/popular?page=$page", headers)

    override fun popularMangaParse(response: Response) = mangaListParse(response.asJsoup())

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/latest?page=$page", headers)

    override fun latestUpdatesParse(response: Response) = mangaListParse(response.asJsoup())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // /search only returns results when "q" is non-blank; browsing by
        // genre alone is done through the dedicated /genres/<slug> page.
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart()
        if (query.isBlank() && !genre.isNullOrEmpty()) {
            return GET("$baseUrl/genres/$genre?page=$page", headers)
        }

        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart()
                ?.takeIf { it.isNotEmpty() }
                ?.let { url.addQueryParameter("sort", it) }
            filters.filterIsInstance<StatusFilter>().firstOrNull()?.toUriPart()
                ?.takeIf { it.isNotEmpty() }
                ?.let { url.addQueryParameter("status", it) }
            filters.filterIsInstance<TypeFilter>().firstOrNull()?.toUriPart()
                ?.takeIf { it.isNotEmpty() }
                ?.let { url.addQueryParameter("type", it) }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = mangaListParse(response.asJsoup())

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        Filter.Separator(),
        GenreFilter(),
    )

    private fun mangaListParse(document: Document): MangasPage {
        val mangas = document.select("a.manga-card").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst(".manga-title")?.text()
                    ?: element.selectFirst("img")?.attr("alt").orEmpty()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.select("a:contains(Next Page)").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // ---------------- Manga details ----------------

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.select("h3:contains(Synopsis)").first()?.nextElementSibling()?.text()
            genre = document.select("a[href^=/genres/]").joinToString { it.text().trim() }

            var statusText: String? = null
            document.select("span.text-xs.text-muted-foreground.uppercase.tracking-wider.font-semibold")
                .forEach { label ->
                    val value = label.nextElementSibling()?.text()?.trim() ?: return@forEach
                    when (label.text().trim()) {
                        "Author" -> author = value
                        "Artist" -> artist = value
                        "Status" -> statusText = value
                    }
                }
            status = when {
                statusText.isNullOrEmpty() -> SManga.UNKNOWN
                statusText.orEmpty().contains("ongoing", ignoreCase = true) -> SManga.ONGOING
                statusText.orEmpty().contains("completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ---------------- Chapters ----------------
    // The full chapter list (beyond what is server-rendered as visible <a>
    // tags) is embedded as JSON inside the props="" attribute of the
    // <astro-island component-url=".../ChapterList...js"> element.

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val mangaPath = response.request.url.encodedPath
        val chaptersArray = propsJson(document, "ChapterList")
            ?.get("chapters")?.unwrap()?.jsonArray
            ?: return emptyList()

        return chaptersArray.mapNotNull { entry ->
            val chapterObj = entry.unwrap().jsonObject
            val number = chapterObj["chapterNumber"]?.unwrap()?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val title = chapterObj["title"]?.unwrap()?.let {
                if (it is JsonNull) null else it.jsonPrimitive.contentOrNull
            }
            val publishedAt = chapterObj["publishedAt"]?.unwrap()?.jsonPrimitive?.contentOrNull

            SChapter.create().apply {
                setUrlWithoutDomain("$mangaPath/$number")
                name = title ?: "Chapter $number"
                date_upload = publishedAt?.let { parseDate(it) } ?: 0L
            }
        }
    }

    // ---------------- Pages ----------------
    // Same astro-island props pattern, this time on the
    // <astro-island component-url=".../ReaderUI...js"> element.

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pagesArray = propsJson(document, "ReaderUI")
            ?.get("chapterData")?.unwrap()?.jsonObject
            ?.get("pages")?.unwrap()?.jsonArray
            ?: return emptyList()

        return pagesArray.mapIndexedNotNull { index, entry ->
            val pageObj = entry.unwrap().jsonObject
            val imageUrl = pageObj["url"]?.unwrap()?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            val number = pageObj["number"]?.unwrap()?.jsonPrimitive?.intOrNull ?: (index + 1)
            Page(number - 1, imageUrl = imageUrl)
        }.sortedBy { it.index }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ---------------- Helpers ----------------

    /**
     * Astro serializes component props as nested `[marker, value]` tuples
     * (e.g. `"chapterNumber":[0,7628]` or `"chapters":[1,[[0,{...}],...]]`).
     * Regardless of the marker (0 = passthrough value, 1 = Array), index 1
     * is always the payload to keep descending into.
     */
    private fun JsonElement.unwrap(): JsonElement = this.jsonArray[1]

    private fun propsJson(document: Document, componentUrlMarker: String) = document.select("astro-island[component-url*=$componentUrlMarker]").first()
        ?.attr("props")
        ?.let { json.parseToJsonElement(it).jsonObject }

    private fun parseDate(dateStr: String): Long = try {
        Instant.parse(dateStr).toEpochMilli()
    } catch (e: DateTimeParseException) {
        0L
    }
}
