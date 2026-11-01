package eu.kanade.tachiyomi.extension.es.inmortalscan

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class InmortalScan :
    Madara(
        "Inmortal Scan",
        "https://scan-inmortal.com",
        "es",
        SimpleDateFormat("MMM dd, yyyy", Locale("es")),
    ) {
    override val mangaSubString = "mg"

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val useNewChapterEndpoint = true

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/$mangaSubString/?catalog_page=$page", headers)

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun popularMangaSelector() = "article.scanim-catalog-card"

    override fun popularMangaNextPageSelector() = "a.scanim-catalog-page[rel=next]"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val link = element.selectFirst("a.scanim-catalog-card__cover")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        title = link.attr("aria-label")
        thumbnail_url = link.selectFirst("img")?.let { imageFromElement(it) }
    }

    override val mangaDetailsSelectorTitle = "h1"
    override val mangaDetailsSelectorGenre = ".scanim-series-genres a"
    override val mangaDetailsSelectorDescription = ".scanim-series-description"
    override val mangaDetailsSelectorThumbnail = "figure.scanim-series-cover img"
    override val mangaDetailsSelectorStatus = ".scanim-series-status"

    // Paid chapters link to "#" and can't be read without an account.
    override fun chapterListSelector() = "li.wp-manga-chapter:not(.premium-block)"
}
