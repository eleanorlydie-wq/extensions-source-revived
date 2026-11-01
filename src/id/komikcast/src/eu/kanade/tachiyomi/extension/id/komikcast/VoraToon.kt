package eu.kanade.tachiyomi.extension.id.komikcast

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class VoraToon : HttpSource() {

    // Formerly "Komik Cast"
    override val id = 972717448578983812

    override val name = "VoraToon"

    override val baseUrl = "https://voratoon.co"

    override val lang = "id"

    override val supportsLatest = true

    private val baseHost = baseUrl.toHttpUrl().host

    private val apiUrl = "$baseUrl/api/proxy"

    override val client = network.client.newBuilder()
        .addInterceptor(::proxyCookieInterceptor)
        .rateLimit(3) { it.encodedPath.contains("/api/") }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "application/json")
        .set("Accept-language", "en-US,en;q=0.9,id;q=0.8")
        .set("Referer", "$baseUrl/")

    // The API answers 403 until the home page has set the vt_proxy cookie.
    private fun proxyCookieInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 403 || request.url.host != baseHost || !request.url.encodedPath.contains("/api/")) {
            return response
        }
        response.close()

        chain.proceed(
            request.newBuilder()
                .url(baseUrl)
                .removeHeader("Accept")
                .build(),
        ).close()

        return chain.proceed(request)
    }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int) = seriesRequest(page, "popularity")

    override fun popularMangaParse(response: Response) = parseSeriesList(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int) = seriesRequest(page, "latest")

    override fun latestUpdatesParse(response: Response) = parseSeriesList(response)

    private fun seriesRequest(page: Int, sort: String): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("includeMeta", "true")
            .addQueryParameter("take", "30")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", sort)
            .addQueryParameter("sortOrder", "desc")
            .build()
        return GET(url, headers)
    }

    private fun parseSeriesList(response: Response): MangasPage {
        val result = response.parseAs<SeriesListResponse>()
        val hasNextPage = result.meta?.let { (it.page ?: 0) < (it.lastPage ?: 0) } ?: false
        return MangasPage(result.data.map { it.toSManga() }, hasNextPage)
    }

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("includeMeta", "true")
            .addQueryParameter("take", "30")
            .addQueryParameter("page", page.toString())
        if (query.isNotEmpty()) {
            url.addQueryParameter("title", query)
        }
        val filter = StringBuilder()
        filters.filterIsInstance<UriFilter>().forEach { it.addToFilter(filter) }
        if (filter.isNotEmpty()) {
            url.addQueryParameter("filter", filter.toString())
        }
        filters.filterIsInstance<UriQueryFilter>().forEach { it.addToQuery(url) }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = parseSeriesList(response)

    override fun getFilterList() = FilterList(
        SortFilter(),
        SortOrderFilter(),
        StatusFilter(),
        FormatFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    // ============================== Details ===============================

    override fun mangaDetailsRequest(manga: SManga) = GET("$apiUrl/series/${manga.getSlug(baseUrl)}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<SeriesDetailResponse>().data.toSManga()

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga) = GET("$apiUrl/series/${manga.getSlug(baseUrl)}/chapters", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.pathSegments.let { it[it.size - 2] }
        return response.parseAs<ChapterListResponse>().data.map { it.toSChapter(slug) }
    }

    // ============================== Pages =================================

    override fun pageListRequest(chapter: SChapter): Request {
        val (slug, index) = chapter.getSlugAndIndex(baseUrl)
        return GET("$apiUrl/series/$slug/chapters/$index", headers)
    }

    override fun pageListParse(response: Response): List<Page> = response.parseAs<ChapterDetailResponse>().data.toPageList()

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
