package eu.kanade.tachiyomi.extension.es.lectormangalat

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.Calendar
import kotlin.time.Duration.Companion.seconds

/**
 * The site was fully rebuilt (Astro + Vuetify frontend, backed by an api.ikigaicomics.lat
 * media/API host) and no longer resembles a Madara/WordPress theme at all: no wp-content,
 * no wp-json, no admin-ajax, and the manga listing/detail/reader markup is completely
 * different (e.g. `/comics`, `a.card-cover-link`, `div#chapters-list`, `img.reader-page-img`).
 * This class was therefore rewritten from scratch as a plain HttpSource instead of a Madara
 * subclass; every selector below was verified against a live fetch of lectormangass.net.
 */
class LectorMangaLat :
    HttpSource(),
    ConfigurableSource {

    override val name = "LectorManga.lat"

    override val baseUrl = "https://lectormangass.net"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .setRandomUserAgent()

    // Required by the repo's randomua lint: sources using :lib:randomua must state the browsable
    // URL explicitly rather than inherit it.
    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comics?sort=view_count&order=desc&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/comics?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // ============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val orderFilter = filters.filterIsInstance<OrderFilter>().firstOrNull()

        val genreSlug = genreFilter?.selectedValue().orEmpty()
        val path = if (genreSlug.isNotEmpty()) "/comics/genre/$genreSlug" else "/comics"

        val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())

            if (query.isNotBlank()) {
                addQueryParameter("search", query)
            }

            statusFilter?.selectedValue()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("statuses", it)
            }

            sortFilter?.selectedValue()?.takeIf { it.isNotEmpty() }?.let { sortValue ->
                addQueryParameter("sort", sortValue)
                addQueryParameter("order", orderFilter?.selectedValue()?.takeIf { it.isNotEmpty() } ?: "desc")
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        OrderFilter(),
        GenreFilter(),
        StatusFilter(),
    )

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("a.card-cover-link").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.attr("title")
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }

        val hasNextPage = document.selectFirst("a.v-pagination__navigation[aria-label=\"Next page\"]") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Details ===============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h1.info-title")?.text().orEmpty()
            thumbnail_url = document.selectFirst("img.cover-img-fallback")?.attr("abs:src")
            description = document.selectFirst("div.info-desc-text")?.text()
            genre = document.select("div.genre-chips-wrap a").joinToString { it.text() }
            status = when (document.selectFirst("a[href*=statuses=]")?.text()?.lowercase()?.trim()) {
                "en emisión", "en emision" -> SManga.ONGOING
                "finalizado" -> SManga.COMPLETED
                "cancelada", "cancelado" -> SManga.CANCELLED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("div#chapters-list > div[data-chapter-num]").map { element ->
            val anchor = element.selectFirst("a")!!
            val number = element.attr("data-chapter-num")

            SChapter.create().apply {
                setUrlWithoutDomain(anchor.attr("href"))
                name = "Capítulo $number"
                chapter_number = number.toFloatOrNull() ?: -1f
                date_upload = anchor.selectFirst("div.text--disabled.text-caption > div.d-flex > div")
                    ?.text()
                    ?.let { parseRelativeDate(it) } ?: 0L
            }
        }
    }

    // ================================ Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        return document.select("div.reader-pages img.reader-page-img").mapIndexed { i, element ->
            Page(i, imageUrl = element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // =============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }

    // Parses strings like "hace 3 sem" (3 weeks ago) into a timestamp.
    private fun parseRelativeDate(date: String): Long {
        val number = NUMBER_REGEX.find(date)?.value?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()

        return when {
            date.containsWord("seg") -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
            date.containsWord("min") -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
            date.containsWord("hora") -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
            date.containsWord("día", "dia") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
            date.containsWord("sem") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number * 7) }.timeInMillis
            date.containsWord("mes") -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
            date.containsWord("año", "ano") -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
            else -> 0L
        }
    }

    private fun String.containsWord(vararg words: String): Boolean = words.any { this.contains(it, ignoreCase = true) }

    companion object {
        private val NUMBER_REGEX = """(\d+)""".toRegex()
    }
}
