package eu.kanade.tachiyomi.extension.en.plutoscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

// The site migrated off the Madara/WordPress theme to a custom Next.js app
// backed by the flamecomics.xyz Next "data" JSON endpoints
// (https://flamecomics.xyz/_next/data/<buildId>/...). There is no WordPress
// endpoint left to parse, so this is a plain HttpSource that reads the same
// SSG JSON payloads the site itself hydrates from.
class FlameScanslol : HttpSource() {

    override val id = 1001157238479601077

    override val name = "FlameScans.lol"
    override val lang = "en"
    override val supportsLatest = true
    override val baseUrl = "https://flamecomics.xyz"
    private val cdn = "https://cdn.flamecomics.xyz"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override val client = network.client.newBuilder()
        .addInterceptor(::buildIdOutdatedInterceptor)
        .rateLimit(2, 2.seconds)
        .build()

    private val removeSpecialCharsRegex = Regex("[^A-Za-z0-9 ]")

    private fun dataApiReqBuilder() = baseUrl.toHttpUrl().newBuilder().apply {
        addPathSegment("_next")
        addPathSegment("data")
        addPathSegment(buildId)
    }

    private fun imageApiUrlBuilder() = "$cdn/uploads/images/series".toHttpUrl().newBuilder()

    // The whole corpus (~155 series) is returned in one JSON payload and the
    // site's own React code (browse-*.js) does all Status/Type/Year/Order
    // filtering client-side over that array (see `L` reducer in the
    // "81524" browse-filters chunk). We mirror that reducer here, and since
    // OkHttp URL fragments never leave the client, we use one to carry the
    // filter state from *Request through to *Parse without touching the
    // actual network request.
    private fun buildFragment(params: Map<String, String>): String = params.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }

    private fun parseFragment(url: HttpUrl): Map<String, String> = url.encodedFragment.orEmpty()
        .split("&")
        .filter { it.isNotEmpty() }
        .associate { part ->
            val (key, value) = part.split("=", limit = 2)
            key to URLDecoder.decode(value, "UTF-8")
        }

    private fun thumbnailUrl(seriesData: Series) = imageApiUrlBuilder().apply {
        addPathSegment(seriesData.series_id.toString())
        addPathSegment(seriesData.cover)
        addQueryParameter(seriesData.last_edit.toString(), null)
    }.build().toString()

    override fun popularMangaRequest(page: Int): Request = GET(
        dataApiReqBuilder().apply {
            addPathSegment("browse.json")
            encodedFragment(buildFragment(mapOf("page" to "$page")))
        }.build(),
        headers,
    )

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
        OrderFilter(),
        StatusFilter(),
        TypeFilter(),
        YearFilter(),
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var genre = ""
        var order = ""
        val statuses = mutableListOf<String>()
        val types = mutableListOf<String>()
        val years = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> if (filter.state != 0) genre = filter.values[filter.state]
                is OrderFilter -> order = when (filter.state) {
                    1 -> "asc"
                    2 -> "desc"
                    else -> ""
                }
                is StatusFilter -> filter.state.forEach { if (it.state) statuses.add(it.value) }
                is TypeFilter -> filter.state.forEach { if (it.state) types.add(it.name) }
                is YearFilter -> filter.state.forEach { if (it.state) years.add(it.name) }
                else -> {}
            }
        }

        val url = dataApiReqBuilder().apply {
            if (genre.isNotEmpty()) {
                addPathSegment("genre")
                addPathSegment("$genre.json")
            } else {
                addPathSegment("browse.json")
            }
            encodedFragment(
                buildFragment(
                    mapOf(
                        "page" to "$page",
                        "q" to removeSpecialCharsRegex.replace(query.lowercase(), ""),
                        "status" to statuses.joinToString(","),
                        "types" to types.joinToString(","),
                        "year" to years.joinToString(","),
                        "order" to order,
                    ),
                ),
            )
        }.build()

        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(
        dataApiReqBuilder().apply {
            addPathSegment("index.json")
        }.build(),
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage = mangaParse(response) { list ->
        list.sortedByDescending { it.likes ?: it.views ?: 0 }
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaParse(response) { seriesList ->
        val params = parseFragment(response.request.url)
        val query = params["q"].orEmpty()
        val statuses = params["status"].orEmpty().split(",").filter { it.isNotEmpty() }
        val types = params["types"].orEmpty().split(",").filter { it.isNotEmpty() }
        val years = params["year"].orEmpty().split(",").filter { it.isNotEmpty() }
        val order = params["order"].orEmpty()

        var filtered = seriesList.filter { series ->
            val matchesQuery = query.isEmpty() || run {
                val titles = mutableListOf(series.title)
                series.altTitles?.let { titles += it }
                titles.any { title ->
                    query in removeSpecialCharsRegex.replace(title.lowercase(), "")
                }
            }
            val matchesStatus = statuses.isEmpty() || statuses.contains(series.status?.lowercase())
            val matchesType = types.isEmpty() || types.contains(series.type)
            val matchesYear = years.isEmpty() || years.contains(series.year?.toString())
            matchesQuery && matchesStatus && matchesType && matchesYear
        }

        // Mirrors the site's own "Sort Order" toggle, which just flips an
        // alphabetical (title) sort; default leaves the feed order as-is.
        filtered = when (order) {
            "asc" -> filtered.sortedBy { it.title.lowercase() }
            "desc" -> filtered.sortedByDescending { it.title.lowercase() }
            else -> filtered
        }

        filtered
    }

    private fun mangaParse(
        response: Response,
        transform: (List<Series>) -> List<Series>,
    ): MangasPage {
        val seriesData = json.decodeFromString<SearchPageData>(response.body.string())
            .pageProps.series.filter { it.series_id != null }

        val page = parseFragment(response.request.url)["page"]!!.toInt()

        val manga = transform(seriesData).map { toSManga(it) }

        val itemsPerPage = 20
        val startIndex = (page - 1) * itemsPerPage
        if (startIndex >= manga.size) return MangasPage(emptyList(), false)
        val endIndex = minOf(page * itemsPerPage, manga.size)
        return MangasPage(manga.subList(startIndex, endIndex), endIndex < manga.size)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val latestData = json.decodeFromString<LatestPageData>(response.body.string())
        val block = latestData.pageProps.latestEntries.blocks.firstOrNull()
            ?: return MangasPage(emptyList(), false)
        return MangasPage(block.series.map { toSManga(it) }, false)
    }

    private fun toSManga(seriesData: Series) = SManga.create().apply {
        title = seriesData.title
        setUrlWithoutDomain(
            baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("series")
                addPathSegment(seriesData.series_id.toString())
            }.build().toString(),
        )
        thumbnail_url = thumbnailUrl(seriesData)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(
        dataApiReqBuilder().apply {
            val seriesId = ("$baseUrl${manga.url}").toHttpUrl().pathSegments.last()
            addPathSegment("series")
            addPathSegment("$seriesId.json")
            addQueryParameter("id", seriesId)
        }.build(),
        headers,
    )

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val seriesData = json.decodeFromString<MangaDetailsResponseData>(response.body.string()).pageProps.series
        title = seriesData.title
        thumbnail_url = thumbnailUrl(seriesData)
        description = seriesData.description?.let { Jsoup.parseBodyFragment(it).wholeText() }
        genre = (seriesData.tags ?: seriesData.categories)?.let { tags ->
            (listOfNotNull(seriesData.type) + tags).joinToString()
        } ?: seriesData.type
        author = seriesData.author?.joinToString()
        artist = seriesData.artist?.joinToString()
        status = when (seriesData.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "dropped" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = json.decodeFromString<ChapterListResponseData>(response.body.string()).pageProps.chapters
        return chapters.map { chapter ->
            SChapter.create().apply {
                setUrlWithoutDomain(
                    baseUrl.toHttpUrl().newBuilder().apply {
                        addPathSegment("series")
                        addPathSegment(chapter.series_id.toString())
                        addPathSegment(chapter.token)
                    }.build().toString(),
                )
                chapter_number = chapter.chapter.toFloat()
                date_upload = chapter.release_date * 1000
                name = buildString {
                    append("Chapter ${chapter.chapter.toString().removeSuffix(".0")}")
                    if (!chapter.title.isNullOrBlank()) {
                        append(" - ${chapter.title}")
                    }
                }
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(
        dataApiReqBuilder().apply {
            val seriesId = ("$baseUrl${chapter.url}").toHttpUrl().pathSegments[1]
            val token = ("$baseUrl${chapter.url}").toHttpUrl().pathSegments[2]
            addPathSegment("series")
            addPathSegment(seriesId)
            addPathSegment("$token.json")
            addQueryParameter("id", seriesId)
            addQueryParameter("token", token)
        }.build(),
        headers,
    )

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun pageListParse(response: Response): List<Page> {
        val chapter = json.decodeFromString<ChapterPageData>(response.body.string()).pageProps.chapter
        return chapter.images.mapIndexed { idx, image ->
            Page(
                idx,
                imageUrl = imageApiUrlBuilder().apply {
                    addPathSegment(chapter.series_id.toString())
                    addPathSegment(chapter.token)
                    addPathSegment(image.name)
                    addQueryParameter(chapter.release_date.toString(), null)
                }.build().toString(),
            )
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    private fun fetchBuildId(document: Document? = null): String {
        val realDocument = document
            ?: client.newCall(GET(baseUrl, headers)).execute().use { it.asJsoup() }

        val nextData = realDocument.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Failed to find __NEXT_DATA__")

        return json.decodeFromString<NewBuildID>(nextData).buildId
    }

    private var buildId = ""
        get() {
            if (field == "") {
                field = fetchBuildId()
            }
            return field
        }

    private fun buildIdOutdatedInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (
            response.code == 404 &&
            request.url.run {
                host == baseUrl.removePrefix("https://") &&
                    pathSegments.getOrNull(0) == "_next" &&
                    pathSegments.getOrNull(1) == "data" &&
                    fragment != "DO_NOT_RETRY"
            } &&
            response.header("Content-Type")?.contains("text/html") != false
        ) {
            // The 404 page should have the current buildId
            val document = response.asJsoup()
            buildId = fetchBuildId(document)

            // Redo request with new buildId
            val url = request.url.newBuilder()
                .setPathSegment(2, buildId)
                .fragment("DO_NOT_RETRY")
                .build()
            val newRequest = request.newBuilder()
                .url(url)
                .build()

            return chain.proceed(newRequest)
        }

        return response
    }
}
