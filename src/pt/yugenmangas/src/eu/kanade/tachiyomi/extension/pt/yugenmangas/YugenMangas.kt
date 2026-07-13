package eu.kanade.tachiyomi.extension.pt.yugenmangas

import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.time.Instant

class YugenMangas :
    HttpSource(),
    ConfigurableSource {
    private val apiUrlHost by lazy { apiUrl.toHttpUrl().host }

    override val name = "Yugen Mangás"

    private val isCi = System.getenv("CI") == "true"

    override val baseUrl: String get() = when {
        isCi -> defaultBaseUrl
        else -> preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!
    }

    private val defaultBaseUrl: String = "https://beta.taimumangas.com"

    private val apiUrl: String = "https://apiv2.taimumangas.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .rateLimit(2) { it.host == apiUrlHost }
        .build()

    override val versionId = 3

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { domain ->
            if (domain != defaultBaseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }

    // ================================ Popular =======================================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/api/v1/reader/library".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "24")
            .addQueryParameter("sort", "rating")
            .addQueryParameter("adult", "false")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<LibraryResponse>()
        return MangasPage(dto.items.map(SeriesSummaryDto::toSManga), dto.hasNextPage())
    }

    // ================================ Latest =======================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/api/v1/reader/updates".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val dto = response.parseAs<UpdatesResponse>()
        val mangas = dto.items.distinctBy(UpdateItemDto::seriesIdentifier).map(UpdateItemDto::toSManga)
        return MangasPage(mangas, dto.hasMore)
    }

    // ================================ Search =======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/api/v1/reader/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchResponse>()
        return MangasPage(dto.series.map(SeriesSummaryDto::toSManga), false)
    }

    // ================================ Details =======================================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/api/v1/reader/series/${manga.identifier()}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<SeriesDetailDto>().toSManga()

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    // ================================ Chapters =======================================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val response = client.newCall(chapterListRequest(manga, page)).execute()
            val dto = response.parseAs<ChaptersResponse>()
            chapters += dto.items.map { it.toSChapter() }
            if (!dto.hasMore) break
            page++
        }
        return Observable.just(chapters)
    }

    private fun chapterListRequest(manga: SManga, page: Int): Request {
        val url = "$apiUrl/api/v1/reader/series/${manga.identifier()}/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun chapterListRequest(manga: SManga): Request = chapterListRequest(manga, 1)

    override fun chapterListParse(response: Response): List<SChapter> = response.parseAs<ChaptersResponse>().items.map { it.toSChapter() }

    private fun ChapterItemDto.toSChapter() = SChapter.create().apply {
        url = "/reader/$identifier"
        name = "Capítulo ${formatChapterNumber(number)}"
        chapter_number = number.toFloat()
        date_upload = publishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    // ================================ Pages =======================================

    override fun pageListRequest(chapter: SChapter): Request = GET("$apiUrl/api/v1/reader/chapters/${chapter.identifier()}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.parseAs<ChapterDetailDto>()
        return dto.pages.sortedBy(ChapterPageDto::number).mapIndexed { index, src ->
            Page(index, imageUrl = src.url)
        }
    }

    override fun imageUrlParse(response: Response) = ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = URL_PREF_SUMMARY

            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "URL padrão:\n$defaultBaseUrl"

            setDefaultValue(defaultBaseUrl)

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP_MESSAGE, Toast.LENGTH_LONG).show()
                true
            }
        }.also(screen::addPreference)
    }

    // ================================ Utils =======================================

    private fun SManga.identifier() = url.substringAfterLast("/")

    private fun SChapter.identifier() = url.substringAfterLast("/")

    private fun formatChapterNumber(number: Double): String {
        val rounded = number.toLong()
        return if (number == rounded.toDouble()) rounded.toString() else number.toString()
    }

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Editar URL da fonte"
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val RESTART_APP_MESSAGE = "Reinicie o aplicativo para aplicar as alterações"
        private const val URL_PREF_SUMMARY = "Para uso temporário, se a extensão for atualizada, a alteração será perdida."
    }
}
