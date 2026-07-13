@file:Suppress("unused", "SpellCheckingInspection")

package eu.kanade.tachiyomi.extension.en.cmanhua

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class CManhua : HttpSource() {

    override val name = "CManhua"
    override val baseUrl = "https://cmanhua.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular =====================================

    override fun popularMangaRequest(page: Int): Request = browseRequest(orderBy = ORDER_VIEWS)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(applyBrowseFilters(response))

    // ============================== Latest ======================================

    override fun latestUpdatesRequest(page: Int): Request = browseRequest(orderBy = ORDER_UPDATED)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(applyBrowseFilters(response))

    // ============================== Search ======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("Modules/Search/SearchHandler.ashx")
                .addQueryParameter("q", query.trim())
                .build()

            return GET(url, headers)
        }

        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart() ?: STATUS_OPTIONS.first().second
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: SORT_OPTIONS.first().second
        val minChapters = filters.firstInstanceOrNull<ChapterCountFilter>()?.state?.ifBlank { "0" } ?: "0"
        val genreIndexes = filters.firstInstanceOrNull<GenreFilter>()?.state
            ?.withIndex()
            ?.filter { (_, genre) -> genre.state }
            ?.joinToString(",") { (index, _) -> index.toString() }
            .orEmpty()

        return browseRequest(orderBy = sort, status = status, minChapters = minChapters, genreIndexes = genreIndexes)
    }

    override fun searchMangaParse(response: Response): MangasPage = if (response.request.url.encodedPath.contains("SearchHandler.ashx")) {
        parseSearchResults(response)
    } else {
        parseMangaList(applyBrowseFilters(response))
    }

    // ============================== Manga Details ===============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("#MainContent_lblTitle")?.text() ?: throw Exception("Title not found")
            author = document.selectFirst("#MainContent_lblAuthor")?.text()
            status = document.selectFirst("#MainContent_lblStatus")?.text().orEmpty().toStatus()
            genre = document.select("span.cd-tag").joinToString { it.text() }
            description = document.selectFirst("#MainContent_lblDescription")?.text()
            thumbnail_url = document.selectFirst("#MainContent_imgCover")?.absUrl("src")
        }
    }

    // ============================== Chapters ====================================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("li.cd-chapter-item").map { element ->
            val link = element.selectFirst("a.cd-chapter-link") ?: throw Exception("Chapter link not found")
            val name: String = link.text().trim()
            val date: String = element.selectFirst("span.text-muted")?.text()?.trim().orEmpty()

            SChapter.create().apply {
                this.name = name
                setUrlWithoutDomain(link.absUrl("href"))
                date_upload = dateFormat.tryParse(date)
                chapter_number = parseChapterNumber(name)
            }
        }
    }

    // ============================== Pages =======================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img.chapter-image").mapIndexedNotNull { index, image ->
            val src = image.attr("abs:data-src").ifEmpty { image.absUrl("src") }
            if (src.isEmpty()) null else Page(index, imageUrl = src)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    // ============================== Filters =====================================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Filters are ignored when searching by name."),
        SortFilter(),
        StatusFilter(),
        ChapterCountFilter(),
        GenreFilter(GENRES),
    )

    // ============================== Request Builders ============================

    private fun browseRequest(
        orderBy: String,
        status: String = STATUS_OPTIONS.first().second,
        minChapters: String = "0",
        genreIndexes: String = "",
    ): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("Browse")
            .addQueryParameter("orderBy", orderBy)
            .addQueryParameter("status", status)
            .addQueryParameter("minChapters", minChapters)
            .apply {
                if (genreIndexes.isNotEmpty()) {
                    addQueryParameter("genres", genreIndexes)
                }
            }
            .build()

        return GET(url, headers)
    }

    /**
     * cmanhua.com's /Browse listing is an ASP.NET WebForms page: the sort/status/genre controls
     * only take effect through a postback (the query string used to build the initial request is
     * otherwise ignored by the server). This performs that postback using the __VIEWSTATE /
     * __EVENTVALIDATION tokens from the page we just fetched, re-reading our own desired filter
     * values back out of that request's query string.
     *
     * Only the first rendered page is supported: paging beyond it is driven by further postbacks
     * (rptPager link clicks) that depend on the *previous* page's fresh viewstate, which this
     * stateless request/parse cycle has no way to chain reliably.
     */
    private fun applyBrowseFilters(initialResponse: Response): Response {
        val requestUrl = initialResponse.request.url
        val orderBy = requestUrl.queryParameter("orderBy") ?: ORDER_UPDATED
        val status = requestUrl.queryParameter("status").orEmpty()
        val minChapters = requestUrl.queryParameter("minChapters")?.ifBlank { "0" } ?: "0"
        val genreIndexes = requestUrl.queryParameter("genres")
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            .orEmpty()

        val document = initialResponse.asJsoup()
        val viewState = document.selectFirst("input#__VIEWSTATE")?.attr("value")
            ?: throw Exception("Unable to load browse page (missing view state)")
        val viewStateGenerator = document.selectFirst("input#__VIEWSTATEGENERATOR")?.attr("value").orEmpty()
        val eventValidation = document.selectFirst("input#__EVENTVALIDATION")?.attr("value")
            ?: throw Exception("Unable to load browse page (missing event validation)")

        val formBuilder = FormBody.Builder()
            .add("__EVENTTARGET", "ctl00\$MainContent\$btnSearch")
            .add("__EVENTARGUMENT", "")
            .add("__VIEWSTATE", viewState)
            .add("__VIEWSTATEGENERATOR", viewStateGenerator)
            .add("__EVENTVALIDATION", eventValidation)
            .add("ctl00\$MainContent\$txtMinChap", minChapters)
            .add("ctl00\$MainContent\$ddlStatus", status)
            .add("ctl00\$MainContent\$ddlOrderBy", orderBy)
            .add("ctl00\$MainContent\$ddlLang", "")

        for (index in genreIndexes) {
            val genre = GENRES.getOrNull(index) ?: continue
            formBuilder.add("ctl00\$MainContent\$chkGenres\$$index", genre.id)
        }

        val browseUrl = "$baseUrl/Browse"
        val request = POST(browseUrl, headersBuilder().set("Referer", browseUrl).build(), formBuilder.build())
        return client.newCall(request).execute()
    }

    // ============================== Utilities ===================================

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangaList = document.select("div.col > a:has(div.comic-card)").mapNotNull { anchor ->
            val url: String = anchor.absUrl("href")
            if (url.isEmpty()) return@mapNotNull null

            val title: String = anchor.selectFirst("div.comic-title")?.text() ?: return@mapNotNull null
            val thumbnail: String = anchor.selectFirst("img")?.absUrl("src").orEmpty()

            SManga.create().apply {
                this.title = title
                if (thumbnail.isNotEmpty()) {
                    thumbnail_url = thumbnail
                }
                setUrlWithoutDomain(url)
            }
        }

        // See applyBrowseFilters: only the first postback-rendered page is supported.
        return MangasPage(mangaList, false)
    }

    private fun parseSearchResults(response: Response): MangasPage {
        val body = response.body.string()
        val jsonElement = runCatching { jsonInstance.parseToJsonElement(body) }.getOrNull()
        val results = jsonElement as? JsonArray ?: return MangasPage(emptyList(), false)

        val mangaList = results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val cover = obj["cover"]?.jsonPrimitive?.contentOrNull.orEmpty()

            SManga.create().apply {
                this.title = title
                if (cover.isNotEmpty()) {
                    thumbnail_url = if (cover.startsWith("http")) cover else baseUrl + cover
                }
                setUrlWithoutDomain("/comic/$slug")
            }
        }

        return MangasPage(mangaList, false)
    }

    // ============================== Helpers =====================================

    private fun String.toStatus(): Int = when (lowercase(Locale.ROOT)) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun parseChapterNumber(name: String): Float = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: -1f

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.ROOT)

    companion object {
        private const val ORDER_UPDATED = "updated_desc"
        private const val ORDER_VIEWS = "views_desc"

        private val CHAPTER_NUMBER_REGEX = Regex("""章节\s*([0-9]+(?:\.[0-9]+)?)""")
    }
}
