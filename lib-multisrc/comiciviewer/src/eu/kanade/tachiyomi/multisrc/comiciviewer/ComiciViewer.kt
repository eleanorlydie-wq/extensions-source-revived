package eu.kanade.tachiyomi.multisrc.comiciviewer

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

abstract class ComiciViewer(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : HttpSource(),
    ConfigurableSource {
    private val preferences: SharedPreferences by getPreferencesLazy()
    protected open val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(ImageInterceptor())
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // /ranking/manga and /category/manga are client-rendered now — they return the SPA shell with
    // no series in the HTML, so the old ranking-box-vertical / category-box-vertical selectors match
    // nothing. /series/list is the catalogue the site still renders server-side; "up" is its default
    // ordering (更新順, by update) and "new" is 新作順. No server-rendered popularity ranking survives.
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/series/list/up/$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = seriesListParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/series/list/new/$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = seriesListParse(response)

    protected fun seriesListParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.series-list-item").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.selectFirst("a.series-list-item-link")!!.absUrl("href"))
                title = element.selectFirst("div.series-list-item-h span")!!.text()
                thumbnail_url = element.selectFirst("img.series-list-item-img")?.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst("a.g-pager-link.mode-active + a.g-pager-link") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query)
                .addQueryParameter("page", (page - 1).toString())
                .addQueryParameter("filter", "series")
                .build()
            return GET(url, headers)
        }
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val browseFilter = filterList.firstInstance<BrowseFilter>()
        val pathAndQuery = getFilterOptions()[browseFilter.state].second
        val url = (baseUrl + pathAndQuery).toHttpUrl().newBuilder().build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.pathSegments

        return when {
            url.contains("series") || url.contains("category") -> seriesListParse(response)

            else -> {
                val document = response.asJsoup()
                val mangas = document.select("div.manga-store-item").map { element ->
                    SManga.create().apply {
                        setUrlWithoutDomain(element.selectFirst("a.c-ms-clk-article")!!.absUrl("href"))
                        title = element.selectFirst("h2.manga-title")!!.text()
                        thumbnail_url = element.selectFirst("source")?.attr("data-srcset")?.substringBefore(" ")?.let { "https:$it" }
                    }
                }
                val hasNextPage = document.selectFirst("li.mode-paging-active + li > a") != null
                return MangasPage(mangas, hasNextPage)
            }
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.select("h1.series-h-title span").last()!!.text()
            author = document.select("div.series-h-credit-user").text()
            artist = author
            description = document.selectFirst("div.series-h-credit-info-text-text")?.text()
            genre = document.select("a.series-h-tag-link").joinToString { it.text().removePrefix("#") }
            thumbnail_url = document.selectFirst("div.series-h-img source")?.attr("data-srcset")?.substringBefore(" ")?.let { "https:$it" }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = "$baseUrl${manga.url}/list".toHttpUrl().newBuilder()
            .addQueryParameter("s", "1")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val showLocked = preferences.getBoolean(SHOW_LOCKED_PREF_KEY, true)
        val document = response.asJsoup()

        return document.select("div.series-ep-list-item").mapNotNull {
            val link = it.selectFirst("a.g-episode-link-wrapper")!!
            val isFree = it.selectFirst("span.free-icon-new") != null
            val isTicketLocked = it.selectFirst("img[data-src*='free_charge_ja.svg']") != null
            val isCoinLocked = it.selectFirst("img[data-src*='coin.svg']") != null

            if (!showLocked && !isFree) {
                return@mapNotNull null
            }

            SChapter.create().apply {
                val chapterUrl = link.absUrl("data-href")
                if (chapterUrl.isNotEmpty()) {
                    setUrlWithoutDomain(chapterUrl)
                } else {
                    url = response.request.url.toString() + "#" + link.absUrl("data-article") + DUMMY_URL_SUFFIX
                }

                name = link.selectFirst("span.series-ep-list-item-h-text")!!.text()
                when {
                    isTicketLocked -> name = "🔒 $name"
                    isCoinLocked -> name = "\uD83E\uDE99 $name"
                }

                date_upload = dateFormat.tryParse(it.selectFirst("time")?.attr("datetime"))
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.url.endsWith(DUMMY_URL_SUFFIX)) {
            throw Exception("Log in via WebView to read purchased chapters and refresh the entry.")
        }
        return super.pageListRequest(chapter)
    }

    override fun pageListParse(response: Response): List<Page> {
        val newHeaders = super.headersBuilder()
            .set("Referer", response.request.url.toString())
            .build()

        val document = response.asJsoup()
        val viewer = document.selectFirst("#comici-viewer") ?: throw Exception("You need to log in via WebView to read this chapter or purchase this chapter.")
        val comiciViewerId = viewer.attr("comici-viewer-id")
        val memberJwt = viewer.attr("data-member-jwt")
        val requestUrl = "$baseUrl/book/contentsInfo".toHttpUrl().newBuilder()
            .addQueryParameter("comici-viewer-id", comiciViewerId)
            .addQueryParameter("user-id", memberJwt)
            .addQueryParameter("page-from", "0")

        val getPages = requestUrl.addQueryParameter("page-to", "1").build()
        val pageTo = client.newCall(GET(getPages, newHeaders)).execute()
        val pageToParse = pageTo.parseAs<ViewerResponse>().totalPages.toString()
        val getAllPages = requestUrl.setQueryParameter("page-to", pageToParse).build()
        val pages = client.newCall(GET(getAllPages, newHeaders)).execute()

        return pages.parseAs<ViewerResponse>().result.map {
            val url = it.imageUrl.toHttpUrl().newBuilder()
                .fragment(it.scramble)
                .build()

            Page(it.sort, imageUrl = url.toString())
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_LOCKED_PREF_KEY
            title = "Show locked chapters"
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    protected open class BrowseFilter(vals: Array<String>) : Filter.Select<String>("Filter by", vals)

    // The ?type=…&day=… query form these used to point at now returns the empty SPA shell.
    // The site links these path forms itself (note the camelCase "oneShot"), and each one
    // server-renders series-list-item blocks.
    protected open fun getFilterOptions(): List<Pair<String, String>> = listOf(
        Pair("更新順", "/series/list/up/1"),
        Pair("新作順", "/series/list/new/1"),
        Pair("読み切り", "/category/manga/oneShot/1"),
        Pair("完結", "/category/manga/complete/1"),
        Pair("月曜日", "/category/manga/day/1/1"),
        Pair("火曜日", "/category/manga/day/2/1"),
        Pair("水曜日", "/category/manga/day/3/1"),
        Pair("木曜日", "/category/manga/day/4/1"),
        Pair("金曜日", "/category/manga/day/5/1"),
        Pair("土曜日", "/category/manga/day/6/1"),
        Pair("日曜日", "/category/manga/day/7/1"),
    )

    override fun getFilterList() = FilterList(
        BrowseFilter(getFilterOptions().map { it.first }.toTypedArray()),
    )

    // Unsupported
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    companion object {
        private const val SHOW_LOCKED_PREF_KEY = "pref_show_locked_chapters"
        private const val DUMMY_URL_SUFFIX = "NeedLogin"
    }
}
