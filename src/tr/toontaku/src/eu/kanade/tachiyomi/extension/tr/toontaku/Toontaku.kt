package eu.kanade.tachiyomi.extension.tr.toontaku

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class Toontaku : HttpSource() {

    override val name = "Toontaku"

    override val baseUrl = "https://toontaku.com"

    override val lang = "tr"

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api"

    override val client = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int) = seriesRequest(page, "totalViews,desc")

    override fun popularMangaParse(response: Response) = parseSeries(response)

    // ============================== Latest ================================

    // /api/chapters/new is cursor-paginated, so remember the cursor the previous page handed out
    private var latestCursor: String? = null

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) {
            latestCursor = null
        }
        val url = "$apiUrl/chapters/new".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", LATEST_PAGE_SIZE.toString())
            if (page > 1) {
                addQueryParameter("cursor", latestCursor ?: throw Exception("Sayfa bulunamadı, listeyi yenileyin"))
            }
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = response.parseAs<ApiResponse<LatestChaptersDto>>().data
        latestCursor = data.nextCursor
        val mangas = data.items.filter { it.contentKind == "IMAGE_CHAPTER" }
            .map { it.toSManga() }
            .distinctBy { it.url }
        return MangasPage(mangas, !data.isEnd)
    }

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("search", query.trim())
            }
            filters.firstInstanceOrNull<TypeFilter>()?.toUriPart()?.let { addQueryParameter("type", it) }
            filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()?.let { addQueryParameter("status", it) }
            filters.firstInstanceOrNull<GenreFilter>()?.toUriPart()?.let { addQueryParameter("includeGenres", it) }
            if (filters.firstInstanceOrNull<OnlyFreeFilter>()?.state == true) {
                addQueryParameter("onlyFree", "true")
            }
        }
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "totalViews,desc"
        return seriesRequest(page, sort, url)
    }

    override fun searchMangaParse(response: Response) = parseSeries(response)

    private fun seriesRequest(
        page: Int,
        sort: String,
        url: HttpUrl.Builder = "$apiUrl/series".toHttpUrl().newBuilder(),
    ): Request {
        url.apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", PAGE_SIZE.toString())
            addQueryParameter("sortBy", sort.substringBefore(","))
            addQueryParameter("sortOrder", sort.substringAfter(","))
            // the site also hosts light novels (TEXT_CHAPTER)
            addQueryParameter("contentKind", "IMAGE_CHAPTER")
        }
        return GET(url.build(), headers)
    }

    private fun parseSeries(response: Response): MangasPage {
        val data = response.parseAs<ApiResponse<SeriesListDto>>().data
        return MangasPage(data.series.map { it.toSManga() }, data.page < data.totalPages)
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        OnlyFreeFilter(),
        GenreFilter(),
    )

    // ============================== Details ===============================

    override fun getMangaUrl(manga: SManga) = "$baseUrl/seri/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga) = GET("$apiUrl/series/slug/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<ApiResponse<SeriesDetailsResponse>>().data.series.toSManga()

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    // the chapter endpoint needs the internal series id, which only the details response has
    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.pathSegments.last()
        val id = response.parseAs<ApiResponse<SeriesDetailsResponse>>().data.series.id
        val url = "$apiUrl/chapters/series/$id".toHttpUrl().newBuilder()
            .addQueryParameter("limit", CHAPTER_LIMIT.toString())
            .addQueryParameter("sortBy", "chapterNumber")
            .addQueryParameter("sortOrder", "desc")
            .build()
        return client.newCall(GET(url, headers)).execute()
            .parseAs<ApiResponse<ChapterListDto>>().data.chapters
            // locked chapters answer 403 unless bought on the site; a logged-in WebView session gets canRead=true
            .filter { it.canRead }
            .map { it.toSChapter(slug) }
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url.substringBefore('?')

    // ============================== Pages =================================

    override fun pageListRequest(chapter: SChapter): Request {
        val url = (baseUrl + chapter.url).toHttpUrl()
        return GET("$apiUrl/chapters/${url.queryParameter("id")}?increment_view=false", headers)
    }

    override fun pageListParse(response: Response): List<Page> = response.parseAs<ApiResponse<ChapterDto>>().data.imageUrls
        .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    companion object {
        private const val PAGE_SIZE = 24
        private const val LATEST_PAGE_SIZE = 60
        private const val CHAPTER_LIMIT = 2000
    }
}
