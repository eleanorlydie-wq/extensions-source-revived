package eu.kanade.tachiyomi.extension.id.pornhwa18

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.util.Calendar

/**
 * The site used to run on a WordPress/Madara theme (paths like /manga/, /series/).
 * It has since been rebuilt as a Qwik (qwik.dev) SSR app: listing pages now live at
 * /type/manga/, /type/manhwa/, /comic-list/ and detail pages at /comic/<slug>/, with
 * completely different markup, so this source no longer extends Madara.
 */
class Pornhwa18 : HttpSource() {

    override val name = "Pornhwa18"
    override val baseUrl = "https://pornhwa18.com"
    override val lang = "id"
    override val supportsLatest = true

    // Listing pages (/, /type/manga/, /type/manhwa/, /comic-list/, /tax/genre/<x>/) are
    // rendered cumulatively: requesting ?page=N returns N * PAGE_SIZE cards, where the
    // first (N-1) * PAGE_SIZE cards are identical (same order) to what page N-1 returned.
    // Confirmed live: /type/manhwa/ (no query) -> 18 cards, ?page=2 -> 36 cards (first 18
    // byte-for-byte identical to the no-query response), ?page=3 -> 54 cards. Same pattern
    // observed on "/" (18 -> 36).
    private val pageSize = 18

    // ============================== Popular ================================
    override fun popularMangaRequest(page: Int): Request = if (page <= 1) GET("$baseUrl/type/manhwa/", headers) else GET("$baseUrl/type/manhwa/?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseCumulativeListing(response)

    // ============================== Latest ==================================
    override fun latestUpdatesRequest(page: Int): Request = if (page <= 1) GET(baseUrl, headers) else GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseCumulativeListing(response)

    private fun parseCumulativeListing(response: Response): MangasPage {
        val document = response.asJsoup()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        // Every manga card on these pages is a single <img alt="Title" data-src="...">
        // wrapped by an <a href="/comic/slug/">; the title text link below the cover
        // points at the same href, so selecting the <img> (not the <a>) avoids double-
        // counting each card. Example seen on /type/manhwa/:
        // <a href="/comic/troublesome-employee-warning-uncensored/" ...>
        //   <img alt="Troublesome Employee Warning (Uncensored)" data-src="https://min.manhwa18.net/..." ...>
        // </a>
        val cards = document.select("img[alt][data-src]")
        val start = (page - 1) * pageSize
        val pageCards = if (start < cards.size) cards.subList(start, cards.size) else emptyList()
        val mangas = pageCards.map { it.toSManga() }
        val hasNextPage = cards.size >= page * pageSize
        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toSManga(): SManga = SManga.create().apply {
        setUrlWithoutDomain(parent()?.attr("abs:href").orEmpty())
        title = attr("alt")
        thumbnail_url = attr("abs:data-src")
    }

    // ============================== Search ==================================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            // Confirmed live: GET /search/<term> (no query string) returns matching
            // cards directly, e.g. /search/daddy returned cards for "Sugar Daddy",
            // "Daddy-in-law", "Do Me, Daddy", "I Became a Sugar Daddy". It does not
            // seem to support a ?page= parameter, so only the first page is fetched.
            return GET("$baseUrl/search/${query.trim()}", headers)
        }

        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.toUriPart() ?: "manhwa"
        val path = if (type == "all") "comic-list" else "type/$type"
        return if (page <= 1) GET("$baseUrl/$path/", headers) else GET("$baseUrl/$path/?page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val path = response.request.url.encodedPath
        return if (path.startsWith("/search/")) {
            val mangas = response.asJsoup().select("img[alt][data-src]").map { it.toSManga() }
            MangasPage(mangas, false)
        } else {
            parseCumulativeListing(response)
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Filter tipe diabaikan saat memakai pencarian teks"),
        TypeFilter(),
    )

    private class TypeFilter :
        Filter.Select<String>(
            "Tipe",
            arrayOf("Manhwa", "Manga", "Semua (Comic List)"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "manga"
            2 -> "all"
            else -> "manhwa"
        }
    }

    // =========================== Manga Details ==============================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        // Detail pages embed a schema.org CreativeWorkSeries JSON-LD block, e.g.:
        // <script type="application/ld+json">{"@context":"https://schema.org",
        // "@type":"CreativeWorkSeries","name":"Sugar Daddy","description":"",
        // "image":"https://min.manhwa18.net/manhwa18/vnwa/images/....webp", ...}</script>
        val ldJson = document.selectFirst("script[type=application/ld+json]:containsData(CreativeWorkSeries)")?.data()

        return SManga.create().apply {
            title = ldJson?.let { Regex("\"name\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
                ?: document.selectFirst("h1[class*=drop-shadow-solid]")?.text().orEmpty()

            thumbnail_url = ldJson?.let { Regex("\"image\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
                ?: document.selectFirst("img.w-full.rounded-md.shadow-md")?.attr("abs:src")

            // Genre chips link to /tax/genre/<slug>/, e.g.
            // <a href="/tax/genre/manhwa/">Manhwa</a> <a href="/tax/genre/mature/">Mature</a>
            genre = document.select("a[href^=/tax/genre/]").eachText().joinToString()

            // Status pill: <div class="... bg-green-800 ...">on-going</div>
            val statusText = document.selectFirst("div.bg-green-800")?.text().orEmpty()
            status = when {
                statusText.contains("ongoing", true) || statusText.contains("on-going", true) -> SManga.ONGOING
                statusText.contains("complete", true) || statusText.contains("tamat", true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            // Info box: <div><p class="text-md font-bold">Author</p>-</div>
            author = document.select("div.mt-4.flex.flex-col.gap-4 > div:has(p:containsOwn(Author))")
                .firstOrNull()?.text()?.removePrefix("Author")?.trim()
                ?.takeIf { it.isNotEmpty() && it != "-" }

            // Synopsis: <div class="mt-4 w-full"><p>...</p></div>, sibling of the
            // genre-chip row (which has no direct <p> child) and above "Chapter List".
            description = document.select("div.mt-4.w-full > p").firstOrNull()?.text()?.ifEmpty { null }
        }
    }

    // ============================== Chapters =================================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        // <div class="mt-4 flex max-h-96 flex-col gap-2 overflow-y-auto">
        //   <a href="/comic/sugar-daddy/chapter-19/">...<p>Chapter 19 </p><p class="text-xs font-medium">2 hour ago</p></a>
        //   ...
        // </div>
        return document.select("div.mt-4.flex.max-h-96.flex-col.gap-2.overflow-y-auto > a").map { element ->
            val paragraphs = element.select("p")
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                name = paragraphs.firstOrNull()?.text()?.trim().orEmpty()
                date_upload = parseRelativeDate(paragraphs.lastOrNull()?.text().orEmpty())
            }
        }
    }

    private fun parseRelativeDate(text: String): Long {
        val trimmed = text.trim()
        val amount = trimmed.takeWhile { it.isDigit() }.toIntOrNull() ?: return 0L
        val field = when {
            trimmed.contains("min") -> Calendar.MINUTE
            trimmed.contains("hour") -> Calendar.HOUR
            trimmed.contains("week") -> Calendar.WEEK_OF_YEAR
            trimmed.contains("mth") || trimmed.contains("month") -> Calendar.MONTH
            trimmed.contains("yr") || trimmed.contains("year") -> Calendar.YEAR
            trimmed.contains("day") -> Calendar.DAY_OF_MONTH
            else -> return 0L
        }
        return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
    }

    // =============================== Pages ====================================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        // Reader images: <img data-src="https://s1.manhwature.com/.../chapters/93410/....jpg" ...>
        // Banner filler images use relative data-src ("/banner.jpg", "/banner-footer.jpg")
        // and are excluded by requiring an absolute (http) data-src.
        return document.select("img[data-src^=http]").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("data-src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used.")
}
