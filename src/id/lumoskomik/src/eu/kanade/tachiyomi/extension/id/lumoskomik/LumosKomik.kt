package eu.kanade.tachiyomi.extension.id.lumoskomik

import android.util.Base64
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.util.Calendar
import kotlin.time.Duration.Companion.seconds

/**
 * LumosKomik was rebuilt on a custom Astro-based frontend (no more WordPress/Madara).
 * Listing lives at /browse, details+chapters at /comic/<slug>, reader pages at
 * /read/<slug>/chapter-N. All selectors below were verified against live HTML.
 */
class LumosKomik : HttpSource() {

    override val name = "LumosKomik"

    override val baseUrl = "https://02.lumosgg.com"

    override val lang = "id"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(5, 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/browse?sort=popular&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browse?sort=latest&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // ============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    val value = filter.selectedValue()
                    if (value.isNotEmpty()) url.addQueryParameter("sort", value)
                }
                is StatusFilter -> {
                    val value = filter.selectedValue()
                    if (value.isNotEmpty()) url.addQueryParameter("status", value)
                }
                is TypeFilter -> {
                    val value = filter.selectedValue()
                    if (value.isNotEmpty()) url.addQueryParameter("type", value)
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    // Card markup (verified on /browse, /browse?sort=popular, /browse?q=...):
    // <div class="group flex flex-col htg-card ..."> <a href="/comic/<slug>" class="... htg-card-cover">
    //   <img src="https://.../cover_xxx.webp" ...></a>
    //   <div class="p-3 ..."><a href="/comic/<slug>" ...><h3 class="... line-clamp-1 ...">Title</h3></a> ...
    // Pagination "next" link: <a ... aria-label="Selanjutnya">, absent on the last page.
    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".htg-card").mapNotNull { element ->
            val link = element.selectFirst("a[href*=/comic/]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.attr("abs:href"))
                title = element.selectFirst("h3")?.text().orEmpty()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst("a[aria-label=Selanjutnya]") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    // Title: single <h1> on the page.
    // Cover: single <img fetchpriority="high"> inside the details <aside>.
    // Genres: <a href="/browse?genre=xxx">Name</a> tags, unique to the genre chip row.
    // Status/Type: "<div class='text-xs text-surface-500 mb-1'>Status</div><div ...><span>ongoing</span></div>"
    // Description: hidden behind base64 anti-scrape obfuscation - the wrapping container carries
    // data-synopsis-id-cls="<id>", and the element with that id carries data-sr="<base64 text>".
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")!!.text()
            thumbnail_url = document.selectFirst("img[fetchpriority=high]")?.attr("abs:src")
            genre = document.select("a[href*=\"/browse?genre=\"]").joinToString { it.text() }
            status = parseStatus(labelValue(document, "Status"))
            description = decodeSynopsis(document)
        }
    }

    private fun labelValue(document: Document, label: String): String? = document.selectFirst("div.text-xs.text-surface-500:containsOwn($label)")
        ?.nextElementSibling()
        ?.text()

    private fun decodeSynopsis(document: Document): String? {
        val synopsisId = document.selectFirst("div[data-synopsis-id-cls]")
            ?.attr("data-synopsis-id-cls")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val encoded = document.selectFirst("[id=$synopsisId]")?.attr("data-sr")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT))
        }.getOrNull()
    }

    private fun parseStatus(status: String?): Int = when (status?.trim()?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    // Chapter rows are <a href="/read/<slug>/chapter-N" data-chapter="196.00 " class="<rowClass> ...">
    // with <span>Chapter 196</span> then a second <span> holding a relative Indonesian timestamp.
    // The row class is a hash that rotates per page (e.g. "_a7707d"), but the same container div
    // that carries data-synopsis-id-cls also exposes it via data-chapter-row-cls, so it's read live.
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val rowClass = document.selectFirst("div[data-chapter-row-cls]")
            ?.attr("data-chapter-row-cls")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        return document.select("a.$rowClass").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                val spans = element.select("span")
                name = spans.firstOrNull()?.text() ?: element.text()
                chapter_number = element.attr("data-chapter").trim().toFloatOrNull() ?: -1f
                date_upload = parseRelativeDate(spans.lastOrNull()?.text())
            }
        }
    }

    private fun parseRelativeDate(text: String?): Long {
        if (text == null) return 0L
        val trimmed = text.trim().lowercase()
        if (trimmed.contains("baru saja")) return System.currentTimeMillis()

        val match = Regex("(\\d+)\\s*(detik|menit|jam|hari|minggu|bulan|tahun)").find(trimmed)
            ?: return 0L
        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val field = when (match.groupValues[2]) {
            "detik" -> Calendar.SECOND
            "menit" -> Calendar.MINUTE
            "jam" -> Calendar.HOUR_OF_DAY
            "hari" -> Calendar.DAY_OF_MONTH
            "minggu" -> Calendar.WEEK_OF_YEAR
            "bulan" -> Calendar.MONTH
            "tahun" -> Calendar.YEAR
            else -> return 0L
        }
        return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
    }

    // ============================== Pages ==============================

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    // Reader images are plain <img src="https://yuucdn.com/.../chapter-01/1-xxx.jpg"> inside
    // #reader-pages - no lazy-loading placeholders to work around, src is already the real URL.
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("#reader-pages img[src]").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
    )

    // Options verified from the /browse filter bar's data-bf-value attributes.
    private class SortFilter :
        Filter.Select<String>(
            "Urutkan",
            arrayOf("Latest Update", "Popular", "Rating", "A-Z"),
        ) {
        fun selectedValue() = arrayOf("latest", "popular", "rating", "az")[state]
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("Semua Status", "ongoing", "completed", "hiatus"),
        ) {
        fun selectedValue() = arrayOf("", "ongoing", "completed", "hiatus")[state]
    }

    private class TypeFilter :
        Filter.Select<String>(
            "Tipe",
            arrayOf("Semua Tipe", "manga", "manhwa", "manhua"),
        ) {
        fun selectedValue() = arrayOf("", "manga", "manhwa", "manhua")[state]
    }
}
