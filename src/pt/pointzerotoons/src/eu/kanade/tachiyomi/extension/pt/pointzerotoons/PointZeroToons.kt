package eu.kanade.tachiyomi.extension.pt.pointzerotoons

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.network.rateLimit
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.IllegalArgumentException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PointZeroToons :
    MangaThemesia(
        "Point Zero Toons",
        "https://kitsuneyako.com",
        "pt-BR",
        dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT),
    ) {
    override val client = super.client.newBuilder()
        .rateLimit(3)
        .build()

    // The site's custom theme pages by path and searches with "s"
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = super.searchMangaRequest(page, query, filters).url
        val order = when (url.queryParameter("order")) {
            "update", "latest" -> "updated"
            "" -> null
            else -> url.queryParameter("order")
        }
        val newUrl = url.newBuilder()
            .removeAllQueryParameters("title")
            .removeAllQueryParameters("page")
            .removeAllQueryParameters("order")
            .apply {
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                    addPathSegment("")
                }
                if (query.isNotBlank()) addQueryParameter("s", query)
                if (order != null) addQueryParameter("order", order)
            }
            .build()

        return GET(newUrl, headers)
    }

    override fun searchMangaSelector() = "article.inkra-catalog-card"

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        thumbnail_url = element.selectFirst("img")?.imgAttr()
        val link = element.selectFirst("h3 a")!!
        title = link.text()
        setUrlWithoutDomain(link.attr("href"))
    }

    override fun searchMangaNextPageSelector() = "a.next.page-numbers"

    override val seriesDetailsSelector = "main.inkra-main"
    override val seriesTitleSelector = "h1.inkra-page-title"
    override val seriesArtistSelector = ".inkra-series-detail-list div:has(dt:contains(Artista)) dd"
    override val seriesAuthorSelector = ".inkra-series-detail-list div:has(dt:contains(Autor)) dd"
    override val seriesDescriptionSelector = ".inkra-series-synopsis"
    override val seriesAltNameSelector = ".inkra-series-alt"
    override val seriesGenreSelector = ".inkra-genre-tags a"
    override val seriesTypeSelector = ".inkra-series-pill--type"
    override val seriesStatusSelector = ".inkra-series-pill--status"
    override val seriesThumbnailSelector = ".inkra-series-cover img"

    override fun chapterListSelector() = "article.inkra-chapter-item"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val link = element.selectFirst("a.inkra-chapter-item__link")!!
        setUrlWithoutDomain(link.attr("href"))
        name = element.selectFirst(".inkra-chapter-item__label")!!.text()
        date_upload = parseRelativeDate(element.selectFirst(".inkra-chapter-item__date")?.text())
    }

    // "2 meses atrás"
    private fun parseRelativeDate(text: String?): Long {
        val match = text?.let(RELATIVE_DATE_REGEX::find) ?: return 0L
        val amount = match.groupValues[1].toInt()
        val field = when (match.groupValues[2]) {
            "minuto" -> Calendar.MINUTE
            "hora" -> Calendar.HOUR_OF_DAY
            "dia" -> Calendar.DAY_OF_MONTH
            "semana" -> Calendar.WEEK_OF_YEAR
            "mes", "mês" -> Calendar.MONTH
            "ano" -> Calendar.YEAR
            else -> return 0L
        }

        return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
    }

    override val pageSelector = ".inkra-reader-pages img"

    override fun pageListParse(document: Document): List<Page> {
        // "ts_reader.run({" in base64
        val script = document.selectFirst("script[src^=data:text/javascript;base64,dHNfcmVhZGVyLnJ1bih7]")
            ?: return super.pageListParse(document)
        val data = Base64.decode(script.attr("src").substringAfter("base64,"), Base64.DEFAULT).toString(Charsets.UTF_8)
        val imageListJson = JSON_IMAGE_LIST_REGEX.find(data)?.destructured?.toList()?.get(0).orEmpty()
        val imageList = try {
            json.parseToJsonElement(imageListJson).jsonArray
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

        return imageList.mapIndexed { i, jsonEl ->
            Page(i, imageUrl = jsonEl.jsonPrimitive.content)
        }
    }

    companion object {
        private val RELATIVE_DATE_REGEX = """(\d+)\s+(minuto|hora|dia|semana|mes|mês|ano)""".toRegex()
    }
}
