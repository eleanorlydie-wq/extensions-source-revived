package eu.kanade.tachiyomi.extension.ru.tomilolib

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
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class TomiloLib :
    HttpSource(),
    ConfigurableSource {

    override val name = "Tomilo-lib"

    override val baseUrl = "https://tomilo-lib.ru"

    override val lang = "ru"

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api"

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    // Only for API calls. Image requests reuse the source headers, and the CDN
    // answers 403 when they ask for application/json.
    private val apiHeaders: Headers by lazy {
        headersBuilder()
            .add("Accept", "application/json")
            .build()
    }

    private val preferences by getPreferencesLazy()

    private val showAdult: Boolean
        get() = preferences.getBoolean(PREF_SHOW_ADULT, false)

    private val hidePaidChapters: Boolean
        get() = preferences.getBoolean(PREF_HIDE_PAID, true)

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int) = GET("$apiUrl/titles?sortBy=views&order=desc&page=$page&limit=$PAGE_LIMIT", apiHeaders)

    override fun popularMangaParse(response: Response) = parseTitles(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int) = GET("$apiUrl/titles?sortBy=updatedAt&order=desc&page=$page&limit=$PAGE_LIMIT", apiHeaders)

    override fun latestUpdatesParse(response: Response) = parseTitles(response)

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/titles".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())

        if (query.isNotBlank()) url.addQueryParameter("search", query)

        var sortBy = "views"
        var order = "desc"

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> filter.selected()?.let { url.addQueryParameter("type", it) }
                is StatusFilter -> filter.selected()?.let { url.addQueryParameter("status", it) }
                is SortFilter -> {
                    sortBy = filter.selectedValue()
                    order = if (filter.ascending()) "asc" else "desc"
                }
                is GenreFilter ->
                    filter.state
                        .filter { it.state }
                        .forEach { url.addQueryParameter("genres", it.name) }
                else -> {}
            }
        }

        url.addQueryParameter("sortBy", sortBy)
        url.addQueryParameter("order", order)
        return GET(url.build(), apiHeaders)
    }

    override fun searchMangaParse(response: Response) = parseTitles(response)

    private fun parseTitles(response: Response): MangasPage {
        val data = response.parseAs<ApiResponse<TitlesData>>().data
        val mangas = data.titles
            .filter { showAdult || !it.isAdult }
            .map { it.toSManga(baseUrl) }
        return MangasPage(mangas, data.pagination.page < data.pagination.pages)
    }

    override fun getFilterList() = FilterList(
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
        Filter.Separator(),
        Filter.Header("Жанры (могут не комбинироваться с текстовым поиском)"),
        GenreFilter(),
    )

    // ============================== Details ===============================

    override fun getMangaUrl(manga: SManga) = "$baseUrl/titles/${manga.url.substringBefore('/')}"

    override fun mangaDetailsRequest(manga: SManga) = GET("$apiUrl/titles/${manga.titleId()}", apiHeaders)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<ApiResponse<TitleDto>>().data.toSManga(baseUrl)

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga) = chaptersRequest(manga.titleId(), 1)

    private fun chaptersRequest(titleId: String, page: Int) = GET("$apiUrl/chapters?titleId=$titleId&page=$page&limit=$CHAPTERS_PER_PAGE", apiHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val titleId = response.request.url.queryParameter("titleId")!!
        val first = response.parseAs<ApiResponse<ChaptersData>>().data
        val chapters = first.chapters.toMutableList()
        for (page in 2..first.pagination.pages) {
            chapters += client.newCall(chaptersRequest(titleId, page)).execute()
                .parseAs<ApiResponse<ChaptersData>>().data.chapters
        }

        return chapters
            .filter { it.isPublished }
            .sortedByDescending { it.chapterNumber }
            .mapNotNull { it.toSChapter(hidePaidChapters) }
    }

    // ============================== Pages =================================

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/chapters/${chapter.url}"

    override fun pageListRequest(chapter: SChapter) = GET("$apiUrl/chapters/${chapter.url}", apiHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<ApiResponse<ChapterDetailDto>>().data
        if (data.pages.isEmpty()) {
            if (data.isPaid) throw Exception("Глава платная и ещё не открыта бесплатно")
            return emptyList()
        }
        return data.pages.mapIndexed { i, url -> Page(i, imageUrl = resolveImageUrl(url, baseUrl)) }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ADULT
            title = "Показывать контент 18+"
            summary = "Включить тайтлы с пометкой 18+ в выдачу"
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_PAID
            title = "Скрывать платные главы"
            summary = "Не показывать ещё не открытые платные главы в списке"
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    private fun SManga.titleId() = url.substringAfterLast('/')

    companion object {
        private const val PAGE_LIMIT = 30
        private const val CHAPTERS_PER_PAGE = 200
        private const val PREF_SHOW_ADULT = "pref_show_adult"
        private const val PREF_HIDE_PAID = "pref_hide_paid"
    }
}
