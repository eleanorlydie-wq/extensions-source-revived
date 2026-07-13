package eu.kanade.tachiyomi.extension.tr.mangadenizi

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class MangaDenizi : HttpSource() {
    override val name = "MangaDenizi"
    override val baseUrl = "https://www.mangadenizi.net"
    override val lang = "tr"
    override val supportsLatest = true

    // The site migrated from a server-rendered Inertia/Laravel app to a Nuxt frontend backed by
    // this JSON API. Listing/details/chapters are served here as plain JSON.
    private val apiUrl = "$baseUrl/api/v1/web"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT)

    // ===============================
    // Popular
    // ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val json = response.parseAs<ApiResponseDto<MangaIndexDto>>().data.manga
        val mangas = json.data.map { it.toSManga() }
        val hasNextPage = json.currentPage < json.lastPage
        return MangasPage(mangas, hasNextPage)
    }

    // ===============================
    // Latest
    // ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ===============================
    // Search
    // ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ===============================
    // Details
    // ===============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/")
        return GET("$apiUrl/manga/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<ApiResponseDto<MangaDetailsDto>>().data.manga.toSManga()

    // ===============================
    // Chapters
    // ===============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = response.parseAs<ApiResponseDto<MangaDetailsDto>>().data.manga
        return json.chapters.map { it.toSChapter(json.slug, dateFormat) }
    }

    // ===============================
    // Pages
    // ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        // Compatibility for old saved URLs
        val url = if (chapter.url.startsWith("/manga/")) {
            chapter.url.replace("/manga/", "/read/")
        } else {
            chapter.url
        }
        return GET(baseUrl + url, headers)
    }

    // The reader page is a Nuxt SSR page: its state is embedded as a flat, index-referencing
    // JSON array in <script id="__NUXT_DATA__">. Rather than reconstructing the whole
    // reference graph, we scan the flat array for the (already-unescaped-by-the-JSON-parser)
    // page image URLs, which appear as plain string elements in ascending page order.
    override fun pageListParse(response: Response): List<Page> {
        val script = response.asJsoup().selectFirst("script#__NUXT_DATA__")!!.data()
        val values = jsonInstance.parseToJsonElement(script).jsonArray
        return values
            .filterIsInstance<JsonPrimitive>()
            .filter { it.isString && "/reader-images/" in it.content }
            .mapIndexed { index, element -> Page(index, imageUrl = element.content) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
