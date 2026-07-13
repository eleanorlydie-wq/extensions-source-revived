package eu.kanade.tachiyomi.extension.en.templescan

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.rateLimit
import keiyoushi.utils.extractNextJs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import kotlin.math.min

class TempleScan :
    HttpSource(),
    ConfigurableSource {

    override val name = "Temple Scan"

    override val lang = "en"

    override val baseUrl = "https://templetoons.com"

    override val supportsLatest = true

    override val versionId = 4

    override fun headersBuilder() = super.headersBuilder()
        .set("referer", "$baseUrl/")
        .set("origin", baseUrl)
        .setRandomUserAgent()

    override val client = network.client.newBuilder()
        .rateLimit(1)
        .build()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = fetchSearchManga(page, "", OrderFilter.POPULAR)

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = fetchSearchManga(page, "", OrderFilter.LATEST)

    private lateinit var seriesCache: List<BrowseSeries>

    // chapter slug -> page image urls, populated by the last chapterListParse call
    private var chapterImagesCache: Map<String, List<String>> = emptyMap()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (page == 1) {
            client.newCall(searchMangaRequest(page, query, filters))
                .execute()
                .use(::parseSearchResponse)
        }

        return Observable.just(parseDirectory(page, query, filters))
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/comics", headers)

    private fun parseSearchResponse(response: Response) {
        seriesCache = response.extractNextJs<List<BrowseSeries>>().orEmpty()
    }

    private fun parseDirectory(page: Int, query: String, filters: FilterList): MangasPage {
        val status = filters.get<StatusFilter>()?.selected
        val mangaList = seriesCache.filter { series ->

            val queryFilter = query.isBlank() ||
                series.title.contains(query, ignoreCase = true) ||
                series.alternativeNames?.contains(query, ignoreCase = true) == true

            val statusFilter = status == null || series.status == status

            queryFilter && statusFilter
        }.let {
            val order = filters.get<OrderFilter>()?.selected

            when (order) {
                "updated" -> it.sortedByDescending { series -> series.updated }
                "created" -> it.sortedByDescending { series -> series.created }
                "views" -> it.sortedByDescending { series -> series.views }
                else -> it
            }
        }

        return MangasPage(
            mangas = mangaList.subList((page - 1) * 20, min(page * 20, mangaList.size))
                .map { it.toSManga() },
            hasNextPage = page * 20 < mangaList.size,
        )
    }

    override fun getFilterList() = getFilters()

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val details = document.extractNextJs<SeriesDetails>(::isSeriesData)!!

        val tags = mutableListOf<String>()

        return SManga.create().apply {
            url = "/comic/${details.slug}"
            title = details.title
            thumbnail_url = details.thumbnail
            status = when (details.status) {
                "Ongoing" -> SManga.ONGOING
                "Hiatus" -> SManga.ON_HIATUS
                "Completed" -> SManga.COMPLETED
                "Canceled" -> SManga.CANCELLED
                "Dropped" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            author = details.author
            artist = details.studio
            description = buildString {
                document.selectFirst("div:has(> p:contains(description))")?.run {
                    selectFirst("p:contains(description)")?.remove()
                    selectFirst("div.mt-7:contains(Additional)")?.remove()
                    selectFirst("div.mt-7:contains(tag)")?.also {
                        tags += it.select("div.flex > p[class^=bg]").eachText()
                    }?.remove()
                    selectFirst("p:contains(tag), p:contains(genre)")?.let {
                        tags += it.text().substringAfter(":")
                            .split(",")
                            .map(String::trim)
                        // sometimes description <p> have the tag/genre, instead of it being separate
                        val tmp = clone()
                        tmp.selectFirst("p:contains(tag), p:contains(genre)")
                            ?.remove()
                        if (tmp.text().isNotBlank()) {
                            it.remove()
                        }
                    }

                    this@buildString.append(wholeText().trim())
                }

                if (!details.alternativeNames.isNullOrBlank()) {
                    if (isNotBlank()) {
                        append("\n\n")
                    }
                    append("Alternative Name: ", details.alternativeNames, "\n")
                }
            }
            genre = buildList {
                add(details.badge)
                add(details.year)
                if (details.adult) {
                    add("Adult")
                }
                addAll(tags.distinct())
            }.filterNotNull().joinToString()
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun chapterListParse(response: Response): List<SChapter> {
        val details = response.extractNextJs<ChapterList>(::isSeriesData)!!
        val mangaSlug = response.request.url.pathSegments.last()

        val chapters = details.seasons.flatMap { it.chapters }
        chapterImagesCache = chapters.associate { it.slug to it.images }

        return chapters.filter {
            it.price == 0
        }.map { chapter ->
            SChapter.create().apply {
                url = "/comic/$mangaSlug/${chapter.slug}"
                name = buildString {
                    append(chapter.name)
                    if (!chapter.title.isNullOrBlank()) {
                        append(": ", chapter.title)
                    }
                }
                date_upload = chapter.created
            }
        }
    }

    // Chapter reader pages no longer embed their own image list; the full page list for
    // every chapter is embedded on the series page instead (same payload chapterListParse
    // reads). Use the cache populated there, falling back to a re-fetch if it's stale/empty
    // (e.g. app restarted and this chapter was opened without visiting the manga first).
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val chapterSlug = chapter.url.substringAfterLast("/")

        chapterImagesCache[chapterSlug]?.let { images ->
            return Observable.just(images.mapIndexed { index, image -> Page(index, imageUrl = image) })
        }

        val mangaSlug = chapter.url.removePrefix("/comic/").substringBefore("/")
        return client.newCall(GET("$baseUrl/comic/$mangaSlug", headers))
            .asObservableSuccess()
            .map { response ->
                val details = response.extractNextJs<ChapterList>(::isSeriesData)!!
                details.seasons.flatMap { it.chapters }
                    .firstOrNull { it.slug == chapterSlug }
                    ?.images
                    .orEmpty()
                    .mapIndexed { index, image -> Page(index, imageUrl = image) }
            }
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }

    private inline fun <reified T : Filter<*>> FilterList.get(): T? = filterIsInstance<T>().firstOrNull()

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}

// The series-details object embedded in the page's Next.js payload; distinguished from other
// objects (e.g. related-series cards) by requiring both the slug and the season/chapter list.
private fun isSeriesData(element: JsonElement): Boolean = element is JsonObject && "series_slug" in element && "Season" in element
