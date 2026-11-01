package eu.kanade.tachiyomi.extension.es.mantrazscan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class ManhwaScan : HttpSource() {

    override val name = "Manhwa Scan"

    override val baseUrl = "https://mantrazscanss.lat"

    override val lang = "es"

    // The site's only listing is sorted by latest update, so a separate Latest tab would be a duplicate.
    override val supportsLatest = false

    override val id: Long = 7172992930543738693

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request = GET(exploreUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val page = response.request.url.encodedPath.substringAfter("/explorar/page/", "1").trim('/').toIntOrNull() ?: 1
        return parseMangaPage(response.document(), page)
    }

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = exploreUrl(page).toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query.trim())

            filters.firstInstanceOrNull<GenreFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("genero", it)
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ============================== Details ==============================
    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.path()

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.path(), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.document()
        return SManga.create().apply {
            title = document.selectFirst("h1")!!.text()
            thumbnail_url = document.selectFirst(".series-cover img")?.absUrl("src")
            genre = document.select("a.genre-tag").map { it.text() }.filter { it.isNotBlank() }.joinToString()
            description = document.selectFirst(".series-desc")?.text()
        }
    }

    // ============================= Chapters ==============================
    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("/")) {
        baseUrl + chapter.url
    } else {
        "$baseUrl/manga/${chapter.url.substringAfter("#")}"
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.document()
        val dates = document.extractNextJs<ChapterDatesDto>()?.dates.orEmpty()

        return document.select("a.ch-row")
            .mapNotNull { element ->
                val url = element.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val number = chapterNumberRegex.find(url)?.groupValues?.get(1) ?: return@mapNotNull null

                SChapter.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    name = "Capítulo $number"
                    chapter_number = number.toFloatOrNull() ?: -1f
                    date_upload = dates[number]?.times(1000) ?: 0L
                }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================
    override fun pageListRequest(chapter: SChapter): Request {
        if (!chapter.url.startsWith("/")) {
            throw Exception("Por favor, actualiza la lista de capítulos para leer este capítulo.")
        }
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> = imageRegex.findAll(response.document().html())
        .map { it.value }
        .distinct()
        .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
        .toList()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================
    override fun getFilterList() = FilterList(GenreFilter())

    // ============================= Utilities =============================
    private fun exploreUrl(page: Int) = if (page == 1) "$baseUrl/explorar/" else "$baseUrl/explorar/page/$page/"

    private fun Response.document(): Document = Jsoup.parse(body.string(), request.url.toString())

    private fun parseMangaPage(document: Document, page: Int): MangasPage {
        val mangas = document.select("div.s-card")
            .mapNotNull { element ->
                val link = element.selectFirst("a.s-card-imglink") ?: return@mapNotNull null
                val title = element.selectFirst("a.s-card-title")?.text()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                SManga.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    this.title = title
                    thumbnail_url = element.selectFirst("img")?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val hasNextPage = document.selectFirst("a[href*=\"/explorar/page/${page + 1}/\"]") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Entries saved before the site moved keep their "id#slug" urls.
    private fun SManga.path() = if (url.startsWith("/")) url else "/manga/${url.substringAfter("#")}/"

    companion object {
        private val chapterNumberRegex = Regex("""capitulo-([0-9]+(?:\.[0-9]+)?)""")
        private val imageRegex = Regex(
            """https://[^"\\ ]+/WP-manga/[^"\\ ]+\.(?:webp|WEBP|jpg|JPG|jpeg|JPEG|png|PNG)""",
        )
    }
}
