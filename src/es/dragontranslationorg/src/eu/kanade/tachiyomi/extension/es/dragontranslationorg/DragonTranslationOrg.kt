package eu.kanade.tachiyomi.extension.es.dragontranslationorg

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class DragonTranslationOrg :
    Madara(
        "DragonTranslation.org",
        "https://dragontranslation.org",
        "es",
        SimpleDateFormat("MMMM dd, yyyy", Locale("es")),
    ) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    // The site's child theme hashes its class names, so select by structure.
    private val gridSelector = "a[title]:has(img.wp-post-image)"

    override fun popularMangaSelector() = gridSelector

    override fun popularMangaFromElement(element: Element) = gridMangaFromElement(element)

    override fun searchMangaSelector() = gridSelector

    override fun searchMangaFromElement(element: Element) = gridMangaFromElement(element)

    private fun gridMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
    }

    override val mangaDetailsSelectorTitle = "h1"
    override val mangaDetailsSelectorThumbnail = "div:has(> .hposter__glare) img.wp-post-image"
    override val mangaDetailsSelectorStatus = ".sinfo-grid div:has(> .l:contains(Estado)) > .v"
    override val mangaDetailsSelectorGenre = "a[href*=/manga-genre/]"

    override fun mangaDetailsParse(document: Document): SManga = super.mangaDetailsParse(document).apply {
        description = document.selectFirst("meta[name=description]")?.attr("content")
    }

    // The series page no longer lists chapters; only the endpoint does.
    override fun chapterListRequest(manga: SManga): Request = xhrChaptersRequest(baseUrl + manga.url.removeSuffix("/"))

    override fun xhrChaptersRequest(mangaUrl: String): Request = POST("$mangaUrl/ajax/chapters/", xhrHeaders)

    override fun pageListParse(document: Document): List<Page> = document.select("img.wp-manga-chapter-img").mapIndexed { index, img ->
        Page(index, imageUrl = imageFromElement(img))
    }

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(3)
        .build()
}
