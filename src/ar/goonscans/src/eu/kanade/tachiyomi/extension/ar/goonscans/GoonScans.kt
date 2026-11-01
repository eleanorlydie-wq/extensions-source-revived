package eu.kanade.tachiyomi.extension.ar.goonscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GoonScans :
    MangaThemesia(
        "Goon Scans",
        "https://goonscans.org",
        "ar",
        mangaUrlDirectory = "/title",
    ) {

    override val sendViewCount = false

    override fun popularMangaRequest(page: Int) = listingRequest(page, "popular")

    override fun latestUpdatesRequest(page: Int) = listingRequest(page, "latest")

    private fun listingRequest(page: Int, order: String) = GET("$baseUrl$mangaUrlDirectory/?order=$order&paged=$page", headers)

    // Search is an AJAX call guarded by a nonce printed in the search page.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return popularMangaRequest(page)

        val nonce = client.newCall(GET("$baseUrl/search/", headers)).execute().use { response ->
            NONCE_REGEX.find(response.body.string())?.groupValues?.get(1)
        } ?: throw Exception("Failed to find the search token")

        val body = FormBody.Builder()
            .add("action", "glsa_live_search")
            .add("term", query)
            .add("nonce", nonce)
            .build()
        return POST("$baseUrl/wp-admin/admin-ajax.php", headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (!response.request.url.encodedPath.endsWith("admin-ajax.php")) return super.searchMangaParse(response)

        val html = response.parseAs<SearchDto>().data?.html.orEmpty()
        val mangas = Jsoup.parse(html, baseUrl).select("a.result-item").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst(".result-title")!!.text()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        return MangasPage(mangas, false)
    }

    // Each card is wrapped in an animated element of the same class.
    override fun searchMangaSelector() = ".webtoon-card:not(:has(.webtoon-card))"

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.cover-link")!!.attr("abs:href"))
        title = element.selectFirst(".title-text")!!.text()
        thumbnail_url = element.selectFirst("img.cover-image")?.attr("abs:src")
    }

    override fun searchMangaNextPageSelector() = ".pagination-numbers .current ~ a"

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1.webtoon-title")!!.text()
        description = document.selectFirst(".description-content")?.text()
        genre = document.select(".genre-tags a").joinToString { it.text() }
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("abs:content")
    }

    override fun chapterListSelector() = "ul.chapter-list li.chapter-item"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.chapter-link")!!.attr("abs:href"))
        name = element.selectFirst(".chapter-number")!!.text()
    }

    // The reader builds its images from a list in an inline script.
    override fun pageListParse(document: Document): List<Page> {
        val urls = IMAGES_REGEX.find(document.html())?.groupValues?.get(1)
            ?: throw Exception("Failed to find the chapter images")
        return "[$urls]".parseAs<List<String>>().mapIndexed { index, url ->
            Page(index, document.location(), if (url.startsWith("/")) "$baseUrl$url" else url)
        }
    }
}

@Serializable
private class SearchDto(
    val data: SearchDataDto? = null,
)

@Serializable
private class SearchDataDto(
    val html: String? = null,
)

private val NONCE_REGEX = Regex("""append\('nonce',\s*'(\w+)'\)""")
private val IMAGES_REGEX = Regex("""imageUrls\s*=\s*\[(.*?)];""")
