package eu.kanade.tachiyomi.extension.id.omicaso

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Omicaso used to be a WordPress "mangathemesia" site (mangaUrlDirectory = "/comik"), but the
 * site has since been fully rebuilt as a custom PHP site: catalog browsing now goes through a
 * JSON endpoint (/api/manga.php) and manga/chapter pages are custom HTML (manga.php, chapter.php).
 * The old theme-based requests 404 unconditionally, so this is a full rewrite to a plain
 * HttpSource that talks to the real endpoints observed on the live site.
 */
class Omicaso : HttpSource() {

    override val name = "Omicaso"

    override val baseUrl = "https://omicaso.org"

    override val lang = "id"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ============================== Popular ================================

    // Site has no real "popular"/"views" sort (verified: sort=views|popular|rating all fall back
    // to "updated"); "chapters" (labelled "Trending" in the site nav) is the closest proxy.
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/manga.php?page=$page&sort=chapters", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    // =============================== Latest =================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/manga.php?page=$page&sort=updated", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // =============================== Search ==================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/manga.php".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("sort", filter.toUriPart())
                is ModeFilter -> url.addQueryParameter("mode", filter.toUriPart())
                is GenreFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("genre", filter.state.trim())
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    private fun parseMangaList(response: Response): MangasPage {
        val data = response.parseAs<MangaListResponseDto>()
        return MangasPage(data.items.map { it.toSManga() }, data.has_more)
    }

    // =========================== Manga Details ===============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst(".detail-poster img")?.attr("abs:src")

            val synopsis = document.selectFirst(".synopsis-box p")?.text().orEmpty()
            val altTitle = document.selectFirst(".detail-alt-title")?.ownText()?.trim().orEmpty()
            description = buildString {
                append(synopsis)
                if (altTitle.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Alternative Name: ").append(altTitle)
                }
            }.trim()

            genre = document.select(".detail-rating-line a").eachText().joinToString(", ")

            document.select(".detail-stat-grid > div").forEach { row ->
                val label = row.selectFirst("span")?.text()?.trim()
                val value = row.selectFirst("strong")?.text()?.trim()
                when (label) {
                    "Status" -> status = when {
                        value?.contains("Ongoing", ignoreCase = true) == true -> SManga.ONGOING
                        value?.contains("Complete", ignoreCase = true) == true -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                    "Author" -> author = value
                    "Artist" -> artist = value
                }
            }
        }
    }

    // ============================== Chapters ==================================

    // The manga page defaults to ascending (oldest-first) chapter order; request descending
    // explicitly so newest chapters come first, as verified live with ?order=desc.
    override fun chapterListRequest(manga: SManga): Request {
        val url = (baseUrl + manga.url).toHttpUrl().newBuilder()
            .setQueryParameter("order", "desc")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a[data-chapter-row]").map { element ->
            SChapter.create().apply {
                url = element.attr("href")
                name = element.selectFirst("strong")?.text() ?: element.text().trim()
                date_upload = element.selectFirst("time")?.attr("datetime")?.let {
                    runCatching { dateFormat.parse(it)?.time }.getOrNull()
                } ?: 0L
            }
        }
    }

    // =============================== Pages =====================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img[data-reader-image]").mapIndexed { index, element ->
            Page(index, "", element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // =============================== Filters ====================================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        ModeFilter(),
        Filter.Separator(),
        GenreFilter(),
    )

    private class SortFilter :
        UriPartFilter(
            "Urutkan",
            arrayOf(
                "Update Terbaru" to "updated",
                "Judul Baru" to "created",
                "Chapter Terbanyak" to "chapters",
                "Judul (A-Z)" to "title",
            ),
        )

    private class ModeFilter :
        UriPartFilter(
            "Konten",
            arrayOf(
                "Semua" to "all",
                "Umum" to "general",
                "Dewasa (18+)" to "adult",
            ),
        )

    private class GenreFilter : Filter.Text("Genre (slug, mis. action, romance, drama)")

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
