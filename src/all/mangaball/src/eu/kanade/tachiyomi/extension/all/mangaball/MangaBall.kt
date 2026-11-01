package eu.kanade.tachiyomi.extension.all.mangaball

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MangaBall(
    override val lang: String,
    private vararg val siteLang: String,
) : HttpSource(),
    ConfigurableSource {

    override val name = "Manga Ball"
    private val domain = "mangaball.com"
    override val baseUrl = "https://$domain"
    private val apiUrl = "$baseUrl/api/v1"
    override val supportsLatest = true
    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request {
        val filters = getFilterList().apply {
            firstInstance<SortFilter>().state = 6
        }

        return searchMangaRequest(page, "", filters)
    }

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) = searchMangaRequest(page, "", getFilterList())

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            return deepLink(query)
        }

        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tagFilters = filters.filterIsInstance<TriStateGroupFilter<String>>()
        val (sortBy, sortOrder) = filters.firstInstance<SortFilter>().selected
        val url = "$apiUrl/title/search-advanced".toHttpUrl().newBuilder().apply {
            query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("keyword", it) }
            addQueryParameter("page", page.toString())
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("sort_order", sortOrder)
            addQueryParameter("language", siteLang.joinToString(","))
            addQueryParameter("adult_mode", if (hideNsfwPreference()) "no_18" else "all")
            addQueryParameter("tag_mode", filters.firstInstance<TagIncludeMode>().selected)
            tagFilters.flatMap { it.included }.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("included_tags", it.joinToString(","))
            }
            tagFilters.flatMap { it.excluded }.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("excluded_tags", it.joinToString(","))
            }
            filters.firstInstance<DemographicFilter>().selected.takeIf { it != "all" }?.let {
                addQueryParameter("demographic", it)
            }
            filters.firstInstance<StatusFilter>().selected.takeIf { it != "all" }?.let {
                addQueryParameter("status", it)
            }
        }.build()

        return GET(url, headers)
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        DemographicFilter(),
        StatusFilter(),
        ContentFilter(),
        FormatFilter(),
        GenreFilter(),
        OriginFilter(),
        ThemeFilter(),
        TagIncludeMode(),
    )

    override fun searchMangaParse(response: Response): MangasPage {
        val data = response.parseAs<SearchResponse>()

        val mangas = data.data.mapNotNull { title ->
            val name = title.name ?: return@mapNotNull null
            SManga.create().apply {
                url = "${title.slug}-${title.id}"
                this.title = name
                thumbnail_url = title.image?.url
            }
        }

        return MangasPage(mangas, data.hasNextPage())
    }

    private fun deepLink(url: String): Observable<MangasPage> {
        val httpUrl = url.toHttpUrl()
        val slug = httpUrl.pathSegments.getOrNull(1)
        if (httpUrl.host != baseUrl.toHttpUrl().host || slug == null) {
            throw Exception("Unsupported url")
        }

        val titleId = when (httpUrl.pathSegments[0]) {
            "title-detail" -> slug

            "chapter-detail" -> client.newCall(GET(httpUrl, headers)).execute().use { response ->
                chapterTitleIdRegex.find(response.body.string())?.groupValues?.get(1)
            } ?: throw Exception("Unsupported url")

            else -> throw Exception("Unsupported url")
        }

        val manga = SManga.create().apply { this.url = titleId }

        return fetchMangaDetails(manga).map {
            MangasPage(listOf(it), false)
        }
    }

    // Library entries are stored as <slug>-<id>, while the site's own links are <id>-<slug>.
    private fun titleId(url: String) = titleIdRegex.find(url)?.value ?: url

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/title/detail/${titleId(manga.url)}", headers)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/title-detail/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<DetailResponse>().data

        return SManga.create().apply {
            url = "${data.slug}-${data.id}"
            title = data.name.orEmpty()
            thumbnail_url = data.image?.url
            genre = buildList {
                when (data.originalLanguage) {
                    "ja", "jp" -> add("Manga")
                    "ko", "kr" -> add("Manhwa")
                    "zh", "cn" -> add("Manhua")
                }
                data.tags.mapNotNullTo(this) { it.name }
            }.joinToString()
            author = data.author.mapNotNull { it.name }.distinct().joinToString()
            description = buildString {
                append(data.description.joinToString("\n\n"))
                data.published?.also { append("\n\nPublished: ", it) }
                if (data.alternateName.isNotEmpty()) {
                    append("\n\nAlternative Names: \n")
                    data.alternateName.forEach { append("- ", it.trim(), "\n") }
                }
            }.trim()
            status = when (data.status) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        // The API answers 400 to "application/json; charset=utf-8", which the String overload would send.
        val body = buildJsonObject { put("title_id", titleId(manga.url)) }
            .toString()
            .toByteArray()
            .toRequestBody("application/json".toMediaType())

        return POST("$apiUrl/chapter/chapter-listing-by-title-id", headers, body)
    }

    override fun chapterListParse(response: Response): List<SChapter> = response.parseAs<ChapterListResponse>().data
        .filter { it.lang in siteLang }
        .map { chapter ->
            SChapter.create().apply {
                url = chapter.id
                val number = chapter.number ?: -1f
                val chapterName = chapter.name.orEmpty().trim()
                name = buildString {
                    val volume = chapter.volume ?: 0f
                    if (volume > 0) {
                        append("Vol. ", volume.toString().removeSuffix(".0"), " ")
                    }
                    val numberText = number.toString().removeSuffix(".0")
                    if (chapterName.contains(numberText)) {
                        append(chapterName)
                    } else {
                        append("Ch. ", numberText, " ", chapterName)
                    }
                }.trim()
                chapter_number = number
                date_upload = dateFormat.tryParse(chapter.createdAt?.substringBefore("."))
                scanlator = chapter.groupName
            }
        }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter-detail/${chapter.url}"

    override fun pageListParse(response: Response): List<Page> {
        // The page list only exists in the escaped server component payload of the reader page.
        val images = pagesRegex.find(response.body.string())
            ?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.parseAs<List<String>>()
            .orEmpty()

        return images.mapIndexed { idx, img ->
            Page(idx, imageUrl = img)
        }
    }

    private val titleIdRegex = Regex("""[a-fA-F0-9]{24}""")
    private val chapterTitleIdRegex = Regex("""title_id\\?":\\?"([a-fA-F0-9]{24})""")
    private val pagesRegex = Regex("""\\?"pages\\?":(\[[^\]]*])""")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = NSFW_PREF
            title = "Hide NSFW content"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private fun hideNsfwPreference() = preferences.getBoolean(NSFW_PREF, false)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}

private const val NSFW_PREF = "nsfw_pref"
