package eu.kanade.tachiyomi.extension.es.skymangas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * SkyMangas was rebuilt as an Angular (SSR) single-page app, it's no longer a
 * MangaThemesia/WordPress site. Only the first page of any listing is
 * server-rendered; further pages/infinite-scroll are fetched client-side
 * against https://api.skymangas.com/api/v1 (e.g. `/manhuas/search`), which
 * requires no auth but isn't used directly here - the SSR /explorar HTML we
 * already scrape honours the same filter/sort query params (see
 * searchMangaRequest and Filters.kt), so pagination is intentionally still
 * not supported, only filtering/sorting of that first page.
 */
class SkyMangas : HttpSource() {

    override val name = "SkyMangas"

    override val baseUrl = "https://skymangas.com"

    override val lang = "es"

    override val supportsLatest = true

    private val dateFormat by lazy {
        SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/explorar", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("sky-manhua-card a.manhua-card").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst(".manhua-card__title")?.text().orEmpty()
                thumbnail_url = element.selectFirst(".manhua-card__cover img.manhua-card__img")?.attr("abs:src")
            }
        }
        return MangasPage(mangas, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/explorar?sort=updated", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =============================== Search ===============================
    // The /explorar page is Angular Universal SSR: the same query params the
    // site's own <sky-browse> component reads on init (see Filters.kt for
    // exactly which ones and how this was confirmed against live bytes) are
    // honoured server-side, so the rendered HTML we already scrape in
    // popularMangaParse changes accordingly - no separate API call needed.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = "$baseUrl/explorar".toHttpUrl().newBuilder()
        if (query.isNotBlank()) urlBuilder.addQueryParameter("q", query)

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> if (filter.state != 0) urlBuilder.addQueryParameter("sort", filter.value)
                is TypeFilter -> if (filter.state != 0) urlBuilder.addQueryParameter("originationId", filter.state.toString())
                is StatusFilter -> if (filter.state != 0) urlBuilder.addQueryParameter("statusId", filter.state.toString())
                is DemographicFilter -> if (filter.state != 0) urlBuilder.addQueryParameter("demographicId", filter.state.toString())
                is AuthorFilter -> if (filter.state.isNotBlank()) urlBuilder.addQueryParameter("author", filter.state)
                is GenreFilter -> {
                    val selected = filter.state.filter { it.state }.joinToString(",") { it.slug }
                    if (selected.isNotEmpty()) urlBuilder.addQueryParameter("genres", selected)
                }
                is TagFilter -> {
                    val selected = filter.state.filter { it.state }.joinToString(",") { it.slug }
                    if (selected.isNotEmpty()) urlBuilder.addQueryParameter("tags", selected)
                }
                else -> {}
            }
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        DemographicFilter(),
        AuthorFilter(),
        GenreFilter(),
        TagFilter(),
    )

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Manga Details ============================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val jsonLd = document.selectFirst("script#page-jsonld")?.data()
        val dto = jsonLd?.let { runCatching { it.parseAs<ComicSeriesDto>() }.getOrNull() }

        return SManga.create().apply {
            title = dto?.name ?: document.selectFirst("h1.hero__title")?.text().orEmpty()
            description = dto?.description
            thumbnail_url = dto?.image
            genre = dto?.genre
            author = dto?.author?.name
            artist = dto?.illustrator?.name
        }
    }

    // ============================== Chapters ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a.chapter-item").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                name = element.selectFirst(".chapter-item__num")?.text()?.trim().orEmpty()
                chapter_number = url.substringAfterLast("/").toFloatOrNull() ?: -1f
                date_upload = dateFormat.tryParse(element.selectFirst(".chapter-item__date")?.attr("title"))
            }
        }
    }

    // =============================== Pages =================================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div.reader__page img.reader__img").mapIndexed { index, img ->
            Page(index, imageUrl = img.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}

@Serializable
data class ComicSeriesDto(
    val name: String? = null,
    val description: String? = null,
    val image: String? = null,
    val genre: String? = null,
    val author: PersonDto? = null,
    val illustrator: PersonDto? = null,
)

@Serializable
data class PersonDto(
    val name: String? = null,
)
