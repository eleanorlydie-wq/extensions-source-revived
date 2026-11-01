package eu.kanade.tachiyomi.extension.fr.aniverse

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class Aniverse : HttpSource() {

    override val name = "Aniverse"

    override val baseUrl = "https://aniverse.fr"

    override val lang = "fr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", getFilterList())

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    // ============================== Latest ================================

    // Ordered by latest chapter release.
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val dto = response.parseAs<MangaListDto>()
        return MangasPage(dto.items.map { it.toSManga() }, dto.hasNextPage)
    }

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/anime/search".toHttpUrl().newBuilder()
            .addQueryParameter("media", "manga")
            .apply {
                if (query.isNotBlank()) addQueryParameter("q", query.trim())
                filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) }
            }
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", "40")
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()
        return MangasPage(dto.data.map { it.toSManga() }, dto.hasNextPage)
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        KindFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    // ============================== Details ===============================

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manga/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga) = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga = response.asJsoup().mangaPage().manga.toSManga()

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val slug = document.mangaPage().manga.slug
        // The page streams its chapters as a separate row that the manga object only references.
        val chapters = document.extractNextJs<List<ChapterDto>> { element ->
            element is JsonArray && (element.firstOrNull() as? JsonObject)?.containsKey("premiumUntil") == true
        }.orEmpty()
        return chapters
            .filterNot { it.isLocked }
            .map { it.toSChapter(slug) }
            .reversed()
    }

    private fun Document.mangaPage(): MangaPageDto = extractNextJs<MangaPageDto> { element ->
        element is JsonObject && "manga" in element && "chapters" in element
    } ?: throw Exception("Impossible d'extraire les données du manga")

    // ============================== Pages =================================

    override fun pageListParse(response: Response): List<Page> {
        // Locked chapters redirect to the sign-in page.
        if (response.request.url.encodedPath.startsWith("/sign-in")) {
            response.close()
            return emptyList()
        }

        val reader = response.asJsoup().extractNextJs<ReaderDto>()
            ?: throw Exception("Impossible d'extraire la liste des pages")
        if (reader.locked) return emptyList()

        return reader.pages.mapIndexed { index, page -> Page(index, imageUrl = page.src) }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
