package eu.kanade.tachiyomi.extension.es.mangashiina

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * MangaMukai (formerly "MangaShiina") stopped being a WordPress/MangaThemesia
 * site: mangamukai.com now serves a Vite/React SPA whose HTML is just
 * `<div id="root">` (rendered client-side), backed by a bespoke JSON API
 * under `/wp-json/mangamukai/v1/`. The old MangaThemesia-based scraping
 * selectors have nothing to match anymore (hence popular_count == 0), so
 * this source talks to that JSON API directly instead.
 *
 * The API has no server-side pagination/search/genre filtering: the site's
 * own bundle fetches the *entire* catalog once (`/catalog`) and slices it in
 * the browser. This source mirrors that: every request re-fetches the full
 * catalog and pages/filters it client-side.
 */
class MangaMukai : HttpSource() {

    override val id: Long = 711368877221654433

    override val name = "Manga Mukai"

    override val baseUrl = "https://mangamukai.com"

    override val lang = "es"

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/wp-json/mangamukai/v1"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private fun paginate(list: List<CatalogMangaDto>, page: Int): MangasPage {
        val start = (page - 1) * PAGE_SIZE
        if (start >= list.size) return MangasPage(emptyList(), false)
        val end = minOf(start + PAGE_SIZE, list.size)
        return MangasPage(list.subList(start, end).map { it.toSManga() }, end < list.size)
    }

    // ── Popular ───────────────────────────────────────────────────────────────
    // No dedicated "popular" endpoint covers the whole catalog (only gender-
    // targeted `popular-men`/`popular-women`), so this ranks the full catalog
    // with series that actually have a published chapter first (readable
    // titles), keeping each group in the catalog's own (recency) order.
    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/catalog?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val result = response.parseAs<CatalogResponseDto>()
        val sorted = result.mangas.sortedByDescending { it.capitulosRecientes.isNotEmpty() }
        return paginate(sorted, page)
    }

    // ── Latest ────────────────────────────────────────────────────────────────
    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/catalog?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val result = response.parseAs<CatalogResponseDto>()
        val sorted = result.mangas.sortedByDescending { it.latestUpdateAt ?: it.fecha ?: "" }
        return paginate(sorted, page)
    }

    // ── Search ────────────────────────────────────────────────────────────────
    // The API ignores unknown query params, so `q`/`genre` are only used to
    // carry the requested filters back into searchMangaParse via the
    // response's own request URL (client-side filtering, like the site does).
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) url.addQueryParameter("q", query)

        val genres = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state
            ?.filter { it.state }?.map { it.name }.orEmpty()
        if (genres.isNotEmpty()) url.addQueryParameter("genre", genres.joinToString(","))

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val query = url.queryParameter("q").orEmpty()
        val genres = url.queryParameter("genre")?.split(",")?.filter { it.isNotBlank() }.orEmpty()

        val result = response.parseAs<CatalogResponseDto>()
        var list = result.mangas

        if (query.isNotBlank()) {
            list = list.filter { it.titulo.contains(query, ignoreCase = true) }
        }
        if (genres.isNotEmpty()) {
            list = list.filter { manga -> genres.all { g -> manga.genres.contains(g) } }
        }

        return paginate(list, page)
    }

    // ── Manga details ──────────────────────────────────────────────────────────
    // manga.url is "/wp-json/mangamukai/v1/manga/{id}", so the default
    // mangaDetailsRequest (GET baseUrl + manga.url) already hits the right
    // endpoint without needing an override.
    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<MangaDetailsDto>().toSManga()

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url.substringAfterLast("/")}"

    // ── Chapters ───────────────────────────────────────────────────────────────
    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        return GET("$apiUrl/series/$id/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> = response.parseAs<ChaptersResponseDto>().chapters.map { it.toSChapter(dateFormat) }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url.substringAfter("id=")}"

    // ── Pages ──────────────────────────────────────────────────────────────────
    // chapter.url is "/wp-json/mangamukai/v1/chapters/content?id={id}", so the
    // default pageListRequest (GET baseUrl + chapter.url) already works.
    override fun pageListParse(response: Response): List<Page> = response.parseAs<ChapterContentDto>().images.mapIndexed { i, img -> Page(i, imageUrl = img) }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ── Filters ──────────────────────────────────────────────────────────────
    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("La búsqueda de texto y los géneros se combinan (AND)"),
        GenreFilter(),
    )

    class GenreCheckBox(name: String) : Filter.CheckBox(name)

    class GenreFilter : Filter.Group<GenreCheckBox>("Género", GENRES.map { GenreCheckBox(it) })

    companion object {
        private const val PAGE_SIZE = 20

        // Real genre tags observed across the live /catalog response.
        private val GENRES = listOf(
            "Romance", "Drama", "Reencarnación", "Fantasía", "Comedia", "Manhwa",
            "Escolar", "Harem", "Mujer", "Hombre", "Magia", "Sistema", "Venganza",
            "Vampiro", "Vampiros", "Supervivencia", "Otome", "Bebés", "Niños",
            "Madrastra", "Madre", "Castigo", "Dominante", "Anime", "B/N",
            "Romance Escolar", "Romance Erótico", "Romance de Oficina", "Romance TL",
            "Romance obsesivo", "Trabajo de oficina", "Ceo", "Presidente",
            "Protagonista Dominante", "Protagonista femenina fuerte",
            "Harén Inverso", "Viaje entre Mundos", "Mundo de bestias",
            "Industria del Entretenimiento", "Hentai", "HOT", "+15", "+16", "+19", "PRE",
        )
    }
}
