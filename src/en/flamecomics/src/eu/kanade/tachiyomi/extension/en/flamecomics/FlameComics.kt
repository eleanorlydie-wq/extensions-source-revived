package eu.kanade.tachiyomi.extension.en.flamecomics

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class FlameComics : HttpSource() {

    override val name = "Flame Comics"

    override val baseUrl = "https://flamecomics.xyz"

    override val lang = "en"

    override val supportsLatest = true

    // Kept from the earlier version of this source, so existing library entries still match.
    override val versionId = 2

    private val baseHost = baseUrl.toHttpUrl().host

    override val client = network.client.newBuilder()
        .addInterceptor(::buildIdInterceptor)
        .rateLimit(2) { it.host == baseHost }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int) = GET(dataUrl { addPathSegment("browse.json") }, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val series = response.parseAs<NextDataDto<BrowseDto>>().pageProps.series
            .sortedBy { it.popularityRank ?: Int.MAX_VALUE }
        return MangasPage(series.mapNotNull { it.toSManga() }, false)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int) = GET(dataUrl { addPathSegment("latest.json") }, headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val series = response.parseAs<NextDataDto<LatestDto>>().pageProps.allSeries
        return MangasPage(series.mapNotNull { it.toSManga() }, false)
    }

    // ============================== Search ================================

    // The site filters its full catalog on the client, so search does the same.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET(
        dataUrl {
            addPathSegment("browse.json")
            fragment(query)
        },
        headers,
    )

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.fragment.orEmpty().normalizeTitle()
        val series = response.parseAs<NextDataDto<BrowseDto>>().pageProps.series
            .filter { query in it.title.normalizeTitle() }
        return MangasPage(series.mapNotNull { it.toSManga() }, false)
    }

    private fun String.normalizeTitle() = SPECIAL_CHARS_REGEX.replace(lowercase(), "")

    // ============================== Details ===============================

    override fun mangaDetailsRequest(manga: SManga) = seriesRequest(manga.url)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<NextDataDto<SeriesPageDto>>().pageProps.series.toSMangaDetails()

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga) = seriesRequest(manga.url)

    override fun chapterListParse(response: Response): List<SChapter> = response.parseAs<NextDataDto<SeriesPageDto>>().pageProps.chapters.map { it.toSChapter() }

    private fun seriesRequest(url: String): Request {
        val seriesId = url.substringAfterLast("/")
        return GET(
            dataUrl {
                addPathSegment("series")
                addPathSegment("$seriesId.json")
                addQueryParameter("id", seriesId)
            },
            headers,
        )
    }

    // ============================== Pages =================================

    override fun pageListRequest(chapter: SChapter): Request {
        val (seriesId, token) = chapter.url.removePrefix("/series/").split("/", limit = 2)
        return GET(
            dataUrl {
                addPathSegment("series")
                addPathSegment(seriesId)
                addPathSegment("$token.json")
                addQueryParameter("id", seriesId)
                addQueryParameter("token", token)
            },
            headers,
        )
    }

    override fun pageListParse(response: Response): List<Page> = response.parseAs<NextDataDto<ChapterPageDto>>().pageProps.chapter.toPages()

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ============================== Build id ==============================

    // Next.js data routes are keyed by the build id, which changes on every deploy. Requests carry
    // a placeholder that the interceptor fills in, refreshing the id once when the site answers 404.
    @Volatile
    private var buildId: String? = null

    private fun dataUrl(path: HttpUrl.Builder.() -> Unit): HttpUrl = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment("_next")
        .addPathSegment("data")
        .addPathSegment(BUILD_ID_PLACEHOLDER)
        .apply(path)
        .build()

    private fun buildIdInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != baseHost || url.pathSegments.getOrNull(2) != BUILD_ID_PLACEHOLDER) {
            return chain.proceed(request)
        }

        val id = buildId ?: fetchBuildId(chain, request)
        val response = chain.proceed(request.withBuildId(id))
        if (response.code != 404) return response
        response.close()

        return chain.proceed(request.withBuildId(fetchBuildId(chain, request)))
    }

    private fun Request.withBuildId(id: String) = newBuilder()
        .url(url.newBuilder().setPathSegment(2, id).build())
        .build()

    private fun fetchBuildId(chain: Interceptor.Chain, request: Request): String {
        val home = request.newBuilder()
            .url(baseUrl)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        val data = chain.proceed(home).asJsoup().selectFirst("script#__NEXT_DATA__")?.data()
        val id = data?.let { BUILD_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: throw IOException("Failed to find buildId")
        buildId = id
        return id
    }

    companion object {
        private const val BUILD_ID_PLACEHOLDER = "build-id"
        private val BUILD_ID_REGEX = Regex(""""buildId"\s*:\s*"([^"]+)"""")
        private val SPECIAL_CHARS_REGEX = Regex("""[^a-z0-9 ]""")
    }
}
