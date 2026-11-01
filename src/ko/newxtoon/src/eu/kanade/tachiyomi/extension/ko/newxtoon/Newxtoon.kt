package eu.kanade.tachiyomi.extension.ko.newxtoon

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class Newxtoon : HttpSource() {

    override val name = "NewXToon"

    override val baseUrl = "https://newxtoon4.com"

    override val lang = "ko"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val jsonHeaders by lazy {
        headersBuilder()
            .set("Accept", "application/json")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int) = comicListRequest(page, "popular")

    override fun popularMangaParse(response: Response) = parseComicList(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int) = comicListRequest(page, "latest")

    override fun latestUpdatesParse(response: Response) = parseComicList(response)

    private fun comicListRequest(page: Int, sort: String): Request {
        val url = "$baseUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
        } else {
            "$baseUrl/comics".toHttpUrl().newBuilder().apply {
                filters.filterIsInstance<UriPartFilter>().forEach { filter ->
                    filter.toUriPart()?.let { addQueryParameter(filter.param, it) }
                }
            }
        }
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = parseComicList(response)

    private fun parseComicList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a.comic-link[href*=/comics/]:not([data-cover-ad])").map { element ->
            SManga.create().apply {
                url = element.absUrl("href").toHttpUrl().pathSegments[1]
                title = element.selectFirst("h3")!!.text()
                thumbnail_url = element.selectFirst("img.cover-image")?.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst("nav[data-site-pagination] a[aria-label=다음 페이지]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("검색어 입력 시 필터는 무시됩니다"),
        SortFilter(),
        CategoryFilter(),
        GenreFilter(),
        PlatformFilter(),
        StatusFilter(),
        WeekdayFilter(),
    )

    // ============================== Details ===============================

    override fun getMangaUrl(manga: SManga) = "$baseUrl/comics/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga) = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val info = response.asJsoup().selectFirst("section[aria-labelledby=comic-title]")!!
        return SManga.create().apply {
            title = info.selectFirst("#comic-title")!!.text()
            thumbnail_url = info.selectFirst("img[alt$=표지]")?.absUrl("src")
            author = info.select("#comic-title + p a").joinToString { it.text() }.ifEmpty { null }
            description = info.selectFirst("[data-comic-description]")?.wholeText()?.trim()
            genre = info.select("a[href*=/comics?category=]").joinToString { it.text() }.ifEmpty { null }
            status = when {
                info.selectFirst("strong:containsOwn(완결)") != null -> SManga.COMPLETED
                info.selectFirst("strong:containsOwn(연재중)") != null -> SManga.ONGOING
                info.selectFirst("strong:containsOwn(휴재)") != null -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga) = chapterPageRequest(manga.url, 1)

    private fun chapterPageRequest(mangaId: String, page: Int): Request {
        val url = "$baseUrl/comics/$mangaId/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "latest")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, jsonHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaId = response.request.url.pathSegments[1]
        val chapters = mutableListOf<SChapter>()
        var dto = response.parseAs<ChapterListDto>()
        dto.chapters.mapTo(chapters) { it.toSChapter(mangaId) }
        var page = 2
        while (dto.hasMore) {
            dto = client.newCall(chapterPageRequest(mangaId, page++)).execute().parseAs<ChapterListDto>()
            dto.chapters.mapTo(chapters) { it.toSChapter(mangaId) }
        }
        return chapters
    }

    // ============================== Pages =================================

    override fun pageListParse(response: Response): List<Page> = response.asJsoup()
        .select("[data-reader-canvas] [data-reader-page] img[data-reader-image]")
        .mapIndexed { index, img -> Page(index, imageUrl = img.absUrl("src")) }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
