package eu.kanade.tachiyomi.extension.zh.mh1234

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class MH1234 : HttpSource() {

    override val baseUrl = "https://m.wmh1234.com"
    override val lang = "zh"
    override val name = "漫画1234"
    override val supportsLatest = true

    // Popular Page

    override fun popularMangaRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("category")
            addPathSegment("order")
            addPathSegment("hits")
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = mangaListParse(response)

    // Latest Page

    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("category")
            addPathSegment("order")
            addPathSegment("addtime")
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response)

    // Search Page

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = if (query.isNotBlank()) {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment(query)
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()
        GET(url, headers)
    } else {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selected?.second ?: "0"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.selected?.second ?: "0"
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected?.second ?: "id"

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("category")
            addPathSegment("tags")
            addPathSegment(genre)
            addPathSegment("finish")
            addPathSegment(status)
            addPathSegment("order")
            addPathSegment(sort)
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()
        GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaListParse(response)

    // Shared manga list parsing

    private fun mangaListParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(MANGA_LIST_SELECTOR).map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        element.selectFirst("a.comic-card__link")!!.let {
            setUrlWithoutDomain(it.absUrl("href"))
            title = it.selectFirst(".comic-card__title")!!.text()
            thumbnail_url = it.selectFirst("img.comic-card__image")?.absUrl("data-src")
        }
    }

    // Manga Detail Page

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            val info = document.select(".mint-work-info > p")
            title = document.selectFirst("#mintWorkTitle")!!.text()
            author = info.getOrNull(0)?.text()?.removeSuffix("著")?.trim()
            genre = info.getOrNull(1)?.ownText()?.split(" ")?.filter(String::isNotBlank)?.joinToString()
            status = when (document.selectFirst(".mint-work-info .mint-tag")?.text()) {
                "连载" -> SManga.ONGOING
                "完结" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            description = document.selectFirst(".mint-intro > div")?.text()
            thumbnail_url = document.selectFirst("img.mint-work-cover")?.absUrl("src")
        }
    }

    // Chapters Page

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("[data-mint-chapters] a[data-chapter-id]").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = element.text()
            }
        }
    }

    // Manga View Page

    override fun pageListParse(response: Response): List<Page> {
        var document = response.asJsoup()
        // Chapters go through an interstitial that redirects to a separate reader host by script.
        document.selectFirst("script:containsData(location.replace)")?.data()
            ?.let { READER_REDIRECT_REGEX.find(it)?.groupValues?.get(1) }
            ?.let { document = client.newCall(GET(it, headers)).execute().asJsoup() }

        return document.select("img.reader-image").mapIndexed { i, img ->
            Page(i, imageUrl = img.absUrl("data-src"))
        }
    }

    // Image

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, imgHeaders)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList() = FilterList(
        GenreFilter(),
        StatusFilter(),
        SortFilter(),
    )

    companion object {
        private const val MANGA_LIST_SELECTOR = ".comic-card"
        private val READER_REDIRECT_REGEX = Regex("""location\.replace\("([^"]+)"\)""")
        private const val NEXT_PAGE_SELECTOR = ".pagination-wrapper a:contains(下一页), .pagination-wrapper a:contains(>)"
    }
}
