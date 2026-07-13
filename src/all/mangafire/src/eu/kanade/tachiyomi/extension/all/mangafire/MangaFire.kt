package eu.kanade.tachiyomi.extension.all.mangafire

import android.annotation.SuppressLint
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import rx.Observable
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private fun Double.formatChapterNumber(): String = if (this == this.toLong().toDouble()) this.toLong().toString() else this.toString()

class MangaFire(
    override val lang: String,
    private val langCode: String = lang,
) : HttpSource(),
    ConfigurableSource {
    override val name = "MangaFire"

    override val baseUrl = "https://mangafire.to"

    override val supportsLatest = true
    private val preferences by getPreferencesLazy()

    override val client = network.client.newBuilder()
        .addInterceptor(ImageInterceptor)
        .apply {
            val naiveTrustManager =
                @SuppressLint("CustomX509TrustManager")
                object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
                }

            val insecureSocketFactory = SSLContext.getInstance("SSL").apply {
                val trustAllCerts = arrayOf<TrustManager>(naiveTrustManager)
                init(null, trustAllCerts, SecureRandom())
            }.socketFactory

            sslSocketFactory(insecureSocketFactory, naiveTrustManager)
            hostnameVerifier { _, _ -> true }
        }
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // disable suggested mangas on Komikku
    // we don't want to spawn N webviews for N search token
    override val disableRelatedMangasBySearch = true

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(
        page,
        "",
        FilterList(SortFilter(defaultValue = "views_total:desc")),
    )

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(
        page,
        "",
        FilterList(SortFilter(defaultValue = "chapter_updated_at:desc")),
    )

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val stdQuery = query.replace("\"", " ").trim()

        val url = "$baseUrl/api/titles".toHttpUrl().newBuilder().apply {
            if (stdQuery.isNotBlank()) {
                addQueryParameter("keyword", stdQuery)
            }

            val filterList = filters.ifEmpty { getFilterList() }
            filterList.filterIsInstance<UriFilter>().forEach {
                it.addToUri(this)
            }

            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<TitleListDto>()
        var entries = dto.items.map { it.toSManga() }
        if (preferences.getBoolean(SHOW_VOLUME_PREF, false)) {
            entries = entries.flatMapTo(ArrayList(entries.size * 2)) { manga ->
                val volume = SManga.create().apply {
                    url = manga.url + VOLUME_URL_SUFFIX
                    title = VOLUME_TITLE_PREFIX + manga.title
                    thumbnail_url = manga.thumbnail_url
                }
                listOf(manga, volume)
            }
        }
        return MangasPage(entries, dto.meta.hasNext)
    }

    // =============================== Filters ==============================

    override fun getFilterList() = FilterList(
        TypeFilter(),
        ContentRatingFilter(),
        GenreModeFilter(),
        GenreFilter(),
        StatusFilter(),
        YearFilter(),
        MinChapterFilter(),
        SortFilter(),
    )

    // =========================== Manga Details ============================

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url.removeSuffix(VOLUME_URL_SUFFIX)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val mangaId = getMangaId(manga.url)
        val url = "$baseUrl/api/titles/$mangaId".toHttpUrl().newBuilder().apply {
            if (manga.url.endsWith(VOLUME_URL_SUFFIX)) {
                fragment(VOLUME_URL_FRAGMENT)
            }
        }.build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseAs<TitleDetailResponseDto>().data

        return SManga.create().apply {
            title = dto.title
            thumbnail_url = dto.poster?.large ?: dto.poster?.medium ?: dto.poster?.small
            status = dto.status.parseStatus()
            description = dto.toDescription()
            author = dto.authors.joinToString { it.title }.takeIf(String::isNotBlank)
            artist = dto.artists.joinToString { it.title }.takeIf(String::isNotBlank)
            genre = (dto.genres.map { it.title } + dto.themes.map { it.title } + dto.demographics.map { it.title })
                .joinToString()
                .takeIf(String::isNotBlank)

            if (response.request.url.fragment == VOLUME_URL_FRAGMENT) {
                title = VOLUME_TITLE_PREFIX + title
            }
        }
    }

    private fun TitleDetailDto.toDescription(): String = buildString {
        synopsisHtml?.let { html ->
            val text = html.replace(Regex("(?i)<br\\s*/?>"), "\n")
                .let { Jsoup.parseBodyFragment(it).body().wholeText() }
                .trim()
            if (text.isNotBlank()) append(text)
        }

        if (altTitles.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("Alternative titles: ${altTitles.joinToString(", ")}")
        }

        type?.takeIf(String::isNotBlank)?.let {
            if (isNotEmpty()) append("\n")
            append("Type: ${it.replaceFirstChar(Char::uppercase)}")
        }

        year?.let {
            if (isNotEmpty()) append("\n")
            append("Published: $it")
        }

        contentRating?.takeIf(String::isNotBlank)?.let {
            if (isNotEmpty()) append("\n")
            append("Content Rating: ${it.replaceFirstChar(Char::uppercase)}")
        }
    }.trim()

    override fun relatedMangaListParse(response: Response): List<SManga> {
        val document = response.asJsoup()
        return document.select(".original a.unit").mapNotNull { element: Element ->
            SManga.create().apply {
                element.attr("href").takeIf(String::isNotBlank)
                    ?.let { setUrlWithoutDomain(it) } ?: return@mapNotNull null
                element.selectFirst(".info h6")?.text()?.takeIf(String::isNotBlank)
                    ?.let { title = it } ?: return@mapNotNull null
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    // MangaFire marks manga as "finished" when their original publication is completed,
    // even if their translation is not complete, so we use the "PUBLISHING_FINISHED" status.
    private fun String?.parseStatus(): Int = when (this) {
        "releasing" -> SManga.ONGOING
        "finished" -> SManga.PUBLISHING_FINISHED
        "on_hiatus" -> SManga.ON_HIATUS
        "discontinued" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.substringBeforeLast("#")

    override fun chapterListRequest(manga: SManga): Request = throw UnsupportedOperationException()

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val mangaId = getMangaId(manga.url)

        if (manga.url.endsWith(VOLUME_URL_SUFFIX)) {
            client.newCall(GET("$baseUrl/api/titles/$mangaId/volumes", headers)).execute()
                .parseAs<VolumeListDto>().items
                .filter { it.language == langCode }
                .map { it.toSChapter(mangaId) }
        } else {
            buildList {
                var page = 1
                while (true) {
                    val url = "$baseUrl/api/titles/$mangaId/chapters".toHttpUrl().newBuilder()
                        .addQueryParameter("language", langCode)
                        .addQueryParameter("sort", "number")
                        .addQueryParameter("order", "desc")
                        .addQueryParameter("page", page.toString())
                        .addQueryParameter("limit", "200")
                        .build()

                    val dto = client.newCall(GET(url, headers)).execute().parseAs<ChapterListDto>()
                    addAll(dto.items.map { it.toSChapter(mangaId) })

                    if (!dto.meta.hasNext) break
                    page++
                }
            }
        }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        val segments = chapter.url.trim('/').split("/")
        val kind = segments.getOrNull(2)
        val id = segments.lastOrNull().orEmpty()
        val endpoint = if (kind == "volume") "volumes" else "chapters"

        return GET("$baseUrl/api/$endpoint/$id", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val pages = response.parseAs<ChapterPagesResponseDto>().data.pages
        return pages.mapIndexed { index, page -> Page(index, imageUrl = page.url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_VOLUME_PREF
            title = "Show volume entries in search result"
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private fun getMangaId(url: String): String = url.removeSuffix(VOLUME_URL_SUFFIX).substringAfter("/title/").substringBefore("-")

    private fun TitleDto.toSManga(): SManga = SManga.create().apply {
        setUrlWithoutDomain(this@toSManga.url)
        title = this@toSManga.title
        thumbnail_url = poster?.large ?: poster?.medium ?: poster?.small
    }

    private fun ChapterDto.toSChapter(mangaId: String): SChapter = SChapter.create().apply {
        setUrlWithoutDomain("/title/$mangaId/chapter/$id")
        chapter_number = number?.toFloat() ?: -1f
        name = buildString {
            append("Chapter ")
            append(number?.formatChapterNumber() ?: "?")
            if (this@toSChapter.name.isNotBlank()) {
                append(": ${this@toSChapter.name}")
            }
        }
        date_upload = createdAt * 1000L
        scanlator = type.takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercase)
    }

    private fun VolumeDto.toSChapter(mangaId: String): SChapter = SChapter.create().apply {
        setUrlWithoutDomain("/title/$mangaId/volume/$id")
        chapter_number = number?.toFloat() ?: -1f
        name = buildString {
            append("Volume ")
            append(number?.formatChapterNumber() ?: "?")
            if (this@toSChapter.name.isNotBlank()) {
                append(": ${this@toSChapter.name}")
            }
        }
    }

    @Serializable
    class TitleListDto(
        val items: List<TitleDto> = emptyList(),
        val meta: MetaDto = MetaDto(),
    )

    @Serializable
    class MetaDto(
        val hasNext: Boolean = false,
    )

    @Serializable
    class TitleDto(
        val hid: String,
        val title: String,
        val url: String,
        val poster: PosterDto? = null,
    )

    @Serializable
    class PosterDto(
        val small: String? = null,
        val medium: String? = null,
        val large: String? = null,
    )

    @Serializable
    class TitleDetailResponseDto(val data: TitleDetailDto)

    @Serializable
    class TitleDetailDto(
        val title: String,
        val type: String? = null,
        val status: String? = null,
        val contentRating: String? = null,
        val year: Int? = null,
        val poster: PosterDto? = null,
        val synopsisHtml: String? = null,
        val altTitles: List<String> = emptyList(),
        val genres: List<TagDto> = emptyList(),
        val themes: List<TagDto> = emptyList(),
        val demographics: List<TagDto> = emptyList(),
        val authors: List<TagDto> = emptyList(),
        val artists: List<TagDto> = emptyList(),
    )

    @Serializable
    class TagDto(val title: String)

    @Serializable
    class ChapterListDto(
        val items: List<ChapterDto> = emptyList(),
        val meta: MetaDto = MetaDto(),
    )

    @Serializable
    class ChapterDto(
        val id: Long,
        val number: Double? = null,
        val name: String = "",
        val language: String = "",
        val type: String = "",
        val createdAt: Long = 0L,
    )

    @Serializable
    class VolumeListDto(val items: List<VolumeDto> = emptyList())

    @Serializable
    class VolumeDto(
        val id: Long,
        val number: Double? = null,
        val name: String = "",
        val language: String = "",
    )

    @Serializable
    class ChapterPagesResponseDto(val data: ChapterPagesDto)

    @Serializable
    class ChapterPagesDto(val pages: List<PageImageDto> = emptyList())

    @Serializable
    class PageImageDto(val url: String)

    companion object {
        private const val SHOW_VOLUME_PREF = "show_volume"

        private const val VOLUME_URL_FRAGMENT = "vol"
        private const val VOLUME_URL_SUFFIX = "#$VOLUME_URL_FRAGMENT"
        private const val VOLUME_TITLE_PREFIX = "[VOL] "
    }
}
