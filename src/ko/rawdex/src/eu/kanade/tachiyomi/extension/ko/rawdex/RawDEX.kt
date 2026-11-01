package eu.kanade.tachiyomi.extension.ko.rawdex

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Request
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class RawDEX :
    Madara(
        "RawDEX",
        "https://rawdex.net",
        "ko",
        SimpleDateFormat("dd.MM.yyyy", Locale.ROOT),
    ) {

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override fun popularMangaSelector() = "article.rdx-library-card"

    override fun popularMangaNextPageSelector() = "a.next.page-numbers"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val link = element.selectFirst("h2 a")!!
        setUrlWithoutDomain(link.absUrl("href"))
        title = link.text()
        thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override val mangaDetailsSelectorTitle = "header.rdx-manga-heading h1"
    override val mangaDetailsSelectorAuthor = "dl.rdx-manga-meta div:has(dt:contains(Author)) dd"
    override val mangaDetailsSelectorArtist = "dl.rdx-manga-meta div:has(dt:contains(Artist)) dd"
    override val mangaDetailsSelectorStatus = ".rdx-manga-status"
    override val mangaDetailsSelectorDescription = ".rdx-manga-summary"
    override val mangaDetailsSelectorGenre = ".rdx-manga-tags a"

    // Only the endpoint returns the standard chapter markup with dates.
    override fun chapterListRequest(manga: SManga): Request = POST("$baseUrl${manga.url.removeSuffix("/")}/ajax/chapters/", xhrHeaders)

    override val chapterUrlSuffix = ""

    override val pageListParseSelector = "figure.rdx-reader-page"
}
