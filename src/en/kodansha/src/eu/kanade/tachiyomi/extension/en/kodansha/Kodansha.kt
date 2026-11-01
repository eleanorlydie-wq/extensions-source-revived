package eu.kanade.tachiyomi.extension.en.kodansha

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import java.io.IOException

class Kodansha :
    HttpSource(),
    ConfigurableSource {
    override val name = "Kodansha"
    override val baseUrl = "https://kodansha.us"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client = network.client.newBuilder()
        .addInterceptor(::imageIntercept)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("azuki-organization-key", ORGANIZATION_KEY)

    // Images on the content host are xor-obfuscated.
    private fun imageIntercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful || chain.request().url.host != CONTENT_HOST) {
            return response
        }
        val body = response.body
        val bytes = body.bytes()
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor XOR_KEY).toByte()
        }
        return response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
    }

    // ============================== Listings ==============================

    override fun popularMangaRequest(page: Int) = listRequest(page, "", "popular", null, emptyList())

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<SeriesListDto>()
        val offset = response.request.url.queryParameter("offset")?.toIntOrNull() ?: 0
        return MangasPage(result.mangas.map { it.toSManga() }, offset + PAGE_SIZE < result.totalCount.toInt())
    }

    override fun latestUpdatesRequest(page: Int) = listRequest(page, "", "recent_series", null, emptyList())

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tags = filters.firstInstance<GenreFilter>().state.filter { it.state }.map { it.value }
        return listRequest(
            page,
            query,
            filters.firstInstance<SortFilter>().value,
            filters.firstInstance<StatusFilter>().value,
            tags,
        )
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    private fun listRequest(page: Int, query: String, sort: String, status: String?, tags: List<String>): Request {
        val url = "$API_URL/mangas/v1".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("search_string", query)
            addQueryParameter("sort", sort)
            status?.let { addQueryParameter("release_status", it) }
            if (tags.isNotEmpty()) addQueryParameter("tags", tags.joinToString(","))
            // Novels and print-only series have no readable chapters
            addQueryParameter("series_types", "comic")
            addQueryParameter("format", "digital")
            addQueryParameter("count", PAGE_SIZE.toString())
            addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
        }.build()
        return GET(url, headers)
    }

    // =============================== Details ==============================

    override fun getMangaUrl(manga: SManga) = "$baseUrl/series/${manga.slug()}/"

    // Entries saved before the API moved keep their "slug#number" urls, and the slug still resolves.
    override fun mangaDetailsRequest(manga: SManga): Request = GET("$API_URL/manga/slug/${manga.slug()}/v0", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<SeriesDto>().toSMangaDetails()

    // ============================== Chapters ==============================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val series = client.newCall(mangaDetailsRequest(manga)).execute().parseAs<SeriesDto>()
        val volumes = client.newCall(GET("$API_URL/mangas/${series.uuid}/volumes/v0", headers)).execute()
            .parseAs<VolumeListDto>().volumes.associate { it.uuid to it.label }

        val chaptersUrl = "$API_URL/mangas/${series.uuid}/chapters/v4".toHttpUrl().newBuilder()
            .addQueryParameter("order", "ascending")
            .addQueryParameter("count", "1000")
            .build()
        val hideLocked = preferences.getBoolean(HIDE_LOCKED_PREF_KEY, false)

        client.newCall(GET(chaptersUrl, headers)).execute().parseAs<ChapterListDto>().chapters
            .filter { !hideLocked || it.isFree() }
            .map { it.toSChapter(manga.slug(), volumes[it.volumeUuid]) }
            .reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.substringAfter("#").split(":")
        val slug = parts[0]
        val volume = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return "$baseUrl/series/$slug/"
        val label = parts.getOrNull(2) ?: return "$baseUrl/series/$slug/"
        return "$baseUrl/reader/series/$slug/volume-$volume/chapter-$label/"
    }

    // =============================== Pages ================================

    // Paid chapters answer 401/403, which pageListParse turns into a readable message.
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = client.newCall(pageListRequest(chapter))
        .asObservable()
        .map { pageListParse(it) }

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.substringBefore("#")
        if ('-' !in id) throw Exception("Please refresh the chapter list to read this chapter.")
        return GET("$API_URL/chapters/$id/pages/v1", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        if (response.code == 401 || response.code == 403) {
            response.close()
            throw IOException("This chapter must be purchased on Kodansha to read")
        }
        return response.parseAs<PageListDto>().data.pages.mapIndexed { i, page ->
            Page(i, imageUrl = page.image.webp.maxBy { it.width }.url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = HIDE_LOCKED_PREF_KEY
            title = "Hide paid chapters"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private fun SManga.slug() = url.substringBefore("#")

    companion object {
        private const val API_URL = "https://production.api.azuki.co"
        private const val ORGANIZATION_KEY = "fff36c1f-9b3d-418e-b3a9-d2a537ddac06"
        private const val PAGE_SIZE = 24
        private const val HIDE_LOCKED_PREF_KEY = "hide_locked"
        private const val CONTENT_HOST = "production.image-content.azuki.co"
        private const val XOR_KEY = 174
    }
}
