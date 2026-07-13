package eu.kanade.tachiyomi.extension.th.onemanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable
import java.util.Calendar

/**
 * mangablackcat.com (formerly a MangaThemesia/WordPress site) was rebuilt as a
 * custom Laravel + Livewire application. None of the old WordPress selectors,
 * endpoints or pagination scheme apply any more, so this source no longer
 * extends the MangaThemesia theme and instead talks to the new site directly.
 */
class OneManga : HttpSource() {

    override val name = "One-Manga"

    override val baseUrl = "https://mangablackcat.com"

    override val lang = "th"

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ------------------------------------------------------------------
    // Popular / Latest / Search
    // Confirmed live: GET https://mangablackcat.com/manga?sort=popular&page=N
    // renders server-side (Livewire full render), with items in
    // <article class="manga-card"><a href="…/manga/slug">…<img src="…"><h3>Title</h3>
    // and pagination via <a rel="next" href="manga?page=2">.
    // ------------------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/manga?sort=popular&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga?sort=latest&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = mangaListParse(response)

    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            // Confirmed live: GET https://mangablackcat.com/search?q=sakamoto returns
            // the same article.manga-card markup as the /manga listing.
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
            return GET(url.build(), headers)
        }

        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("sort", filter.selectedValue())
                is StatusFilter -> url.addQueryParameter("status", filter.selectedValue())
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach { genre ->
                        url.addQueryParameter("genres[]", genre.id)
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaListParse(response)

    private fun mangaListParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("article.manga-card > a[href]").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                // ownText() skips the "N"/"UP" status-badge <span> and keeps
                // only the title text node, e.g. `<h3><span>N</span>Bad Business</h3>`.
                title = element.selectFirst("h3")?.ownText()?.trim().orEmpty()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }

        val hasNextPage = document.selectFirst("a[rel=next]") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ------------------------------------------------------------------
    // Manga details
    // Confirmed live: every /manga/{slug} page embeds a ComicSeries JSON-LD
    // block, e.g.
    // {"@context":"https://schema.org","@type":"ComicSeries","name":"Sakamoto Days …",
    //  "image":"https://mangablackcat.com/storage/709/…large.webp","author":{"@type":"Person","name":"Unknown"},
    //  "genre":["Action","Comedy","Sci-Fi","Manga"],"numberOfItems":254}
    // Status ("สถานะ") isn't part of that JSON-LD and is scraped from the
    // sidebar: <dt>สถานะ</dt><dd>กำลังอัพเดท</dd> / <dd>จบแล้ว</dd>.
    // ------------------------------------------------------------------

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val ld = document.selectFirst("script[type=application/ld+json]")
            ?.data()
            ?.let { runCatching { json.decodeFromString<MangaJsonLd>(it) }.getOrNull() }

        return SManga.create().apply {
            title = ld?.name ?: document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = ld?.image
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            author = ld?.author?.name?.takeUnless { it.isBlank() || it == "Unknown" }
            genre = ld?.genre?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            description = ld?.description?.takeUnless { it.isBlank() }
            status = when (document.selectFirst("dt:contains(สถานะ)")?.nextElementSibling()?.text()?.trim()) {
                "กำลังอัพเดท" -> SManga.ONGOING
                "จบแล้ว" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    @Serializable
    private data class MangaJsonLd(
        val name: String? = null,
        val image: String? = null,
        val description: String? = null,
        val author: Author? = null,
        val genre: List<String> = emptyList(),
    ) {
        @Serializable
        data class Author(val name: String? = null)
    }

    // ------------------------------------------------------------------
    // Chapter list
    // Confirmed live: the chapter list on /manga/{slug} is paginated 20-per-page
    // via the SAME url with ?page=N (<nav aria-label="Pagination Navigation">
    // … <a rel="next" href="…/manga/sakamoto-days?page=2">), so all pages must
    // be walked to get the full chapter list. Chapter entries are
    // <a class="chapter-card-link" href="…/manga/sakamoto-days/1.0" data-chapter-number="1">
    //   <h4>ตอนที่ 1</h4><p>6mos ago</p>
    // </a>
    // ------------------------------------------------------------------

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val chapters = mutableListOf<SChapter>()
        val seenUrls = mutableSetOf<String>()
        var page = 1

        while (page <= MAX_CHAPTER_PAGES) {
            val url = "$baseUrl${manga.url}".toHttpUrl().newBuilder()
                .setQueryParameter("page", page.toString())
                .build()
            val document = client.newCall(GET(url, headers)).execute().asJsoup()

            val pageChapters = chaptersFromDocument(document)
            if (pageChapters.isEmpty()) break

            for (chapter in pageChapters) {
                if (seenUrls.add(chapter.url)) chapters.add(chapter)
            }

            val hasNext = document.selectFirst("nav[aria-label=Pagination Navigation] a[rel=next]") != null
            if (!hasNext) break
            page++
        }

        chapters
    }

    override fun chapterListParse(response: Response): List<SChapter> = chaptersFromDocument(response.asJsoup())

    private fun chaptersFromDocument(document: Document): List<SChapter> = document.select("a.chapter-card-link[href]").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            name = element.selectFirst("h4")?.text()?.trim().orEmpty()
            chapter_number = element.attr("abs:href").substringAfterLast("/").toFloatOrNull()
                ?: element.attr("data-chapter-number").toFloatOrNull()
                ?: -1f
            date_upload = parseRelativeDate(element.selectFirst("p")?.text()?.trim())
        }
    }

    private fun parseRelativeDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        // Order matters: "mo" must be tried before the single-letter "m".
        val match = Regex("""(\d+)\s*(mo|s|m|h|d|w|y)""").find(text) ?: return 0L
        val value = match.groupValues[1].toIntOrNull() ?: return 0L
        val calendar = Calendar.getInstance()
        when (match.groupValues[2]) {
            "s" -> calendar.add(Calendar.SECOND, -value)
            "m" -> calendar.add(Calendar.MINUTE, -value)
            "h" -> calendar.add(Calendar.HOUR_OF_DAY, -value)
            "d" -> calendar.add(Calendar.DAY_OF_MONTH, -value)
            "w" -> calendar.add(Calendar.WEEK_OF_YEAR, -value)
            "mo" -> calendar.add(Calendar.MONTH, -value)
            "y" -> calendar.add(Calendar.YEAR, -value)
            else -> return 0L
        }
        return calendar.timeInMillis
    }

    // ------------------------------------------------------------------
    // Page list (reader)
    // Confirmed live: /manga/{slug}/{chapter} renders, per page,
    // x-data="scrambledPage({ boot: JSON.parse('{"mode":"plain",
    //   "image":"https:\/\/cdn.mangablackcat.com\/images\/uploads\/manga\/
    //   sakamoto-days\/chapter-1\/001.jpg"}'), apiUrl: '…/api/scrambled-page/…' })".
    // Every page sampled (ch.1 and ch.266 of Sakamoto Days) used mode "plain", so the
    // literal "image" field is a directly-fetchable URL (verified: HTTP 200 image/jpeg
    // from cdn.mangablackcat.com). No canvas-descrambling logic is implemented since no
    // "scrambled" sample was observed to reverse-engineer.
    // ------------------------------------------------------------------

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val bootRegex = Regex("""scrambledPage\(\{\s*boot:\s*JSON\.parse\('(.*?)'\)""")

        return document.select("div[x-data*=scrambledPage]")
            .mapIndexedNotNull { index, div ->
                val xData = div.attr("x-data")
                val rawBoot = bootRegex.find(xData)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                val decoded = jsStringUnescape(rawBoot)
                val boot = runCatching { json.decodeFromString<ReaderPageBoot>(decoded) }.getOrNull()
                val imageUrl = boot?.image ?: return@mapIndexedNotNull null
                Page(index, "", imageUrl)
            }
    }

    /**
     * The boot payload is JSON, JS-string-escaped once for embedding inside the
     * `x-data="…JSON.parse('…')…"` HTML attribute (e.g. `"` for `"`, and each
     * literal backslash doubled so the inner `\/` JSON escape survives). This undoes
     * that single JS-string-literal escaping layer; the resulting text is then valid
     * JSON and can be decoded normally (kotlinx.serialization unescapes `\/` to `/`).
     */
    private fun jsStringUnescape(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length) {
                val next = input[i + 1]
                when (next) {
                    'u' -> if (i + 5 < input.length) {
                        val code = input.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                        continue
                    }
                    't' -> {
                        sb.append('\t')
                        i += 2
                        continue
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                        continue
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    @Serializable
    private data class ReaderPageBoot(
        val mode: String? = null,
        val image: String? = null,
    )

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ------------------------------------------------------------------
    // Filters
    // sort / status / genres[] query params confirmed live against
    // https://mangablackcat.com/manga (Livewire `manga-filter` component
    // wire:snapshot data changes when these params are supplied).
    // Genre id -> name pairs scraped from the genre-pill checkboxes on that
    // same listing page.
    // ------------------------------------------------------------------

    private class SortFilter :
        Filter.Select<String>(
            "เรียงลำดับ",
            arrayOf("อัพเดทล่าสุด", "ยอดนิยม", "คะแนนสูงสุด", "A-Z"),
        ) {
        fun selectedValue() = when (state) {
            1 -> "popular"
            2 -> "rating"
            3 -> "a-z"
            else -> "latest"
        }
    }

    private class StatusFilter :
        Filter.Select<String>(
            "สถานะ",
            arrayOf("ทั้งหมด", "กำลังอัพเดท", "จบแล้ว"),
        ) {
        fun selectedValue() = when (state) {
            1 -> "ongoing"
            2 -> "completed"
            else -> ""
        }
    }

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)

    private class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("หมวดหมู่", genres)

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        Filter.Separator(),
        GenreFilter(genreList()),
    )

    private fun genreList(): List<Genre> = listOf(
        "1" to "Romance",
        "2" to "Action",
        "3" to "Fantasy",
        "4" to "Drama",
        "5" to "Comedy",
        "6" to "Horror",
        "7" to "School Life",
        "8" to "Isekai",
        "9" to "Sports",
        "10" to "Slice of Life",
        "11" to "Sci-Fi",
        "12" to "Adventure",
        "13" to "Harem",
        "14" to "Supernatural",
        "15" to "Mystery",
        "16" to "Psychological",
        "17" to "Time Travel",
        "18" to "Martial Arts",
        "19" to "Shounen",
        "20" to "Ecchi",
        "21" to "Overpowered",
        "23" to "Immortal",
        "24" to "Manhua",
        "25" to "Cultivation",
        "26" to "System",
        "27" to "Office Life",
        "28" to "Modern Life",
        "29" to "Survival",
        "30" to "Reincarnation",
        "31" to "Adult",
        "32" to "Mature",
        "33" to "Josei",
        "34" to "Historical",
        "35" to "Business",
        "36" to "Modern World",
        "37" to "Wealth Growth",
        "38" to "Investment",
        "39" to "Revenge",
        "40" to "Manhwa",
        "41" to "Manga",
    ).map { (id, name) -> Genre(name, id) }

    private companion object {
        const val MAX_CHAPTER_PAGES = 100
    }
}
