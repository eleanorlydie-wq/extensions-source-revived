package eu.kanade.tachiyomi.extension.tr.nemesisscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Nemesisscans.com was rebuilt as an Angular SPA backed by a separate JSON API
 * host (https://yahsirou.xyz, referenced via <link rel="preconnect"> on the
 * site's index.html). There is no more server-rendered WordPress/MangaThemesia
 * markup to scrape, so this source talks to that JSON API directly instead of
 * extending the MangaThemesia theme.
 */
class Nemesisscans : HttpSource() {

    override val name = "Nemesisscans"

    override val baseUrl = "https://nemesisscans.com"

    private val apiUrl = "https://yahsirou.xyz"

    override val lang = "tr"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3)
        .build()

    // ------------------------------------------------------------------
    // Popular / Latest / Search all hit the same listing endpoint. The
    // API's own "sort" and "search" query params are accepted but do not
    // actually change ordering or filter results (verified live), so
    // pagination via "page" is the only thing that reliably works.
    // ------------------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/series?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseSeriesList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/series?page=$page&sort=-updatedAt", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseSeriesList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseSeriesList(response)

    private fun parseSeriesList(response: Response): MangasPage {
        val dto = response.parseAs<SeriesListResponseDto>()
        val mangas = dto.data.map { it.toSManga() }
        val hasNextPage = dto.pagination.page < dto.pagination.pages
        return MangasPage(mangas, hasNextPage)
    }

    private fun SeriesDto.toSManga() = SManga.create().apply {
        title = romaji() ?: "?"
        url = "/series/$id"
        thumbnail_url = poster
        description = this@toSManga.description?.replace(Regex("(?i)<br\\s*/?>"), "\n")
        genre = genres.joinToString().takeIf { it.isNotEmpty() }
        status = when (this@toSManga.status) {
            "RELEASING" -> SManga.ONGOING
            "FINISHED" -> SManga.COMPLETED
            "CANCELLED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun SeriesDto.romaji(): String? = title.romaji?.takeIf { it.isNotBlank() }
        ?: title.english?.takeIf { it.isNotBlank() }
        ?: title.native?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------
    // Manga details + chapter list are served by the same endpoint.
    // ------------------------------------------------------------------

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<SeriesDetailResponseDto>().series.toSManga()

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = response.parseAs<SeriesDetailResponseDto>()
        val seriesId = dto.series.id
        return dto.episodes
            .sortedByDescending { it.episodeNumber }
            .map { ep ->
                SChapter.create().apply {
                    name = ep.episodeName?.takeIf { it.isNotBlank() } ?: "Bölüm ${ep.episodeNumber.toChapterLabel()}"
                    chapter_number = ep.episodeNumber.toFloat()
                    url = "/series/$seriesId/episodes/${ep.episodeNumber.toChapterLabel()}"
                    date_upload = ep.updatedAt.toDateOrZero()
                }
            }
    }

    // ------------------------------------------------------------------
    // Page list
    // ------------------------------------------------------------------

    override fun pageListRequest(chapter: SChapter): Request = GET("$apiUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.parseAs<EpisodeDetailResponseDto>()
        return dto.episode.images.sortedBy { it.order }.mapIndexed { index, image ->
            Page(index, imageUrl = image.url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun Double.toChapterLabel(): String = if (this == Math.floor(this) && !this.isInfinite()) toInt().toString() else toString()

    private fun String?.toDateOrZero(): Long {
        this ?: return 0L
        return try {
            Instant.parse(this).toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }
    }
}
