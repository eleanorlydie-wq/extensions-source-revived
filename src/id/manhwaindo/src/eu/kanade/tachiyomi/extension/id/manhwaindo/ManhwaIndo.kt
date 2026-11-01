package eu.kanade.tachiyomi.extension.id.manhwaindo

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ManhwaIndo :
    MangaThemesia(
        "Manhwa Indo",
        "https://www.manhwaindo.my",
        "id",
        "/series",
        SimpleDateFormat("MMMM dd, yyyy", Locale.US),
    ) {
    override val hasProjectPage = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")

    override val client = network.client.newBuilder()
        .addInterceptor(::headersInterceptor)
        .rateLimit(4)
        .build()

    // The site's custom theme sorts with "sort" and different values
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = super.searchMangaRequest(page, query, filters).url
        val sort = when (url.queryParameter("order")) {
            "popular" -> "views"
            "update" -> "modified"
            "latest" -> "date"
            "title" -> "title"
            else -> null
        }
        val newUrl = url.newBuilder().removeAllQueryParameters("order").apply {
            if (sort != null) addQueryParameter("sort", sort)
        }.build()

        return GET(newUrl, headers)
    }

    override fun searchMangaSelector() = "a.bm-card.series"

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        thumbnail_url = element.selectFirst("img")?.imgAttr()
        title = element.selectFirst(".bm-card__title")!!.text()
        setUrlWithoutDomain(element.attr("href"))
    }

    override fun searchMangaNextPageSelector() = "a.next.page-numbers"

    override val seriesDetailsSelector = "main.bm-series"
    override val seriesTitleSelector = ".bm-series__title"
    override val seriesArtistSelector = ".bm-meta div:has(dt:contains(Artist)) dd"
    override val seriesAuthorSelector = ".bm-meta div:has(dt:contains(Penulis)) dd"
    override val seriesDescriptionSelector = ".bm-synopsis__body"
    override val seriesAltNameSelector = ".bm-series__alt"
    override val seriesGenreSelector = ".bm-chips a.bm-chip"
    override val seriesTypeSelector = ".bm-series__badges .bm-badge:not(.bm-badge--status)"
    override val seriesStatusSelector = ".bm-badge--status"
    override val seriesThumbnailSelector = ".bm-series__cover img"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        // The page holds only part of the list, the rest comes from the theme's endpoint
        val full = SERIES_ID_REGEX.find(document.html())?.groupValues?.get(1)?.let { seriesId ->
            runCatching { fetchAllChapters(seriesId) }.getOrNull()
        }

        return full?.takeIf { it.isNotEmpty() } ?: document.select("ul.bm-chl__ssr a.bm-chitem").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                name = element.selectFirst(".bm-chitem__name")!!.text()
                date_upload = parseRelativeDate(element.selectFirst(".bm-chitem__time")?.text())
            }
        }
    }

    private fun fetchAllChapters(seriesId: String): List<SChapter> {
        val url = "$baseUrl/wp-json/agc/v1/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("series", seriesId)
            .build()
        val root = client.newCall(GET(url, headers)).execute().use { json.parseToJsonElement(it.body.string()) }
        val items = (root as? JsonObject)?.get("chapters") ?: root

        return items.jsonArray
            .sortedByDescending { it.string("chapter")?.toFloatOrNull() ?: -1F }
            .map { item ->
                SChapter.create().apply {
                    setUrlWithoutDomain(item.string("url")!!)
                    name = item.string("chapter")?.let { "Chapter $it" } ?: item.string("title")!!
                    date_upload = parseRelativeDate(item.string("time"))
                }
            }
    }

    private fun JsonElement.string(key: String) = jsonObject[key]?.jsonPrimitive?.content

    // "60 minutes lalu", "1 day lalu"
    private fun parseRelativeDate(text: String?): Long {
        val match = text?.let(RELATIVE_DATE_REGEX::find) ?: return 0L
        val amount = match.groupValues[1].toInt()
        val field = when (match.groupValues[2]) {
            "minute" -> Calendar.MINUTE
            "hour" -> Calendar.HOUR_OF_DAY
            "day" -> Calendar.DAY_OF_MONTH
            "week" -> Calendar.WEEK_OF_YEAR
            "month" -> Calendar.MONTH
            "year" -> Calendar.YEAR
            else -> return 0L
        }

        return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
    }

    override val pageSelector = ".reader-area img:not(.bm-ad img)"

    override fun pageListParse(response: Response): List<Page> = super.pageListParse(response).distinctBy {
        it.imageUrl!!
    }

    private fun headersInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val urlString = url.toString()
        val newHeaders = request.headers.newBuilder().apply {
            removeAll("X-Requested-With")
            if (urlString.contains("gmbr.pro") || urlString.contains("manhwaindo.my")) {
                set("Sec-Fetch-Site", if (urlString.contains("manhwaindo.my")) "same-origin" else "cross-site")
                set("Sec-Fetch-Mode", "no-cors")
                set("Sec-Fetch-Dest", "image")
            }
        }.build()

        val newRequest = if (url.scheme == "http" && (urlString.contains("gmbr.pro") || urlString.contains("manhwaindo.my"))) {
            request.newBuilder()
                .url(url.newBuilder().scheme("https").build())
                .headers(newHeaders)
                .build()
        } else {
            request.newBuilder()
                .headers(newHeaders)
                .build()
        }

        return chain.proceed(newRequest)
    }

    companion object {
        private val SERIES_ID_REGEX = """agcChapterListConfig\s*=\s*\{\s*seriesId:\s*(\d+)""".toRegex()
        private val RELATIVE_DATE_REGEX = """(\d+)\s+(minute|hour|day|week|month|year)""".toRegex()
    }
}
