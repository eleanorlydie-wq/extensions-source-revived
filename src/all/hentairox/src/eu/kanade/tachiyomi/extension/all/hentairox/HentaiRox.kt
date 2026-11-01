package eu.kanade.tachiyomi.extension.all.hentairox

import eu.kanade.tachiyomi.multisrc.galleryadults.AdvancedTextFilter
import eu.kanade.tachiyomi.multisrc.galleryadults.FavoriteFilter
import eu.kanade.tachiyomi.multisrc.galleryadults.GalleryAdults
import eu.kanade.tachiyomi.multisrc.galleryadults.Genre
import eu.kanade.tachiyomi.multisrc.galleryadults.GenresFilter
import eu.kanade.tachiyomi.multisrc.galleryadults.SortOrderFilter
import eu.kanade.tachiyomi.multisrc.galleryadults.SpeechlessFilter
import eu.kanade.tachiyomi.multisrc.galleryadults.imgAttr
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiRox(
    lang: String = "all",
    override val mangaLang: String = LANGUAGE_MULTI,
) : GalleryAdults(
    "HentaiRox",
    "https://hentairox.com",
    lang = lang,
) {
    override val supportsLatest = true
    override val supportSpeechless: Boolean = true

    override val basicSearchKey = "key"

    override fun popularMangaSelector() = "article.hr-gallery-card"

    override fun popularMangaNextPageSelector() = ".hr-pagination__item.is-active + .hr-pagination__item a"

    override fun Element.mangaUrl() = selectFirst(".hr-gallery-card__cover")?.attr("abs:href")

    override fun Element.mangaThumbnail() = selectFirst(".hr-gallery-card__media img")?.imgAttr()

    override fun Element.mangaLang() = selectFirst(".hr-gallery-card__flag")?.attr("href")
        .orEmpty()
        .removeSuffix("/")
        .substringAfterLast("/")
        .let {
            // Include Speechless in search results
            if (it == LANGUAGE_SPEECHLESS) mangaLang else it
        }

    override fun Element.mangaTitle(selector: String): String? = mangaFullTitle(selector.takeIf { it != ".caption" } ?: ".hr-gallery-card__title").let {
        if (preferences.shortTitle) it?.shortenTitle() else it
    }

    // The site has no language-independent popular listing, the search page covers it.
    override fun popularMangaRequest(page: Int): Request = if (mangaLang.isBlank()) {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment("")
            addQueryParameter("sort", "popular")
            addQueryParameter("page", page.toString())
        }
        GET(url.build(), headers)
    } else {
        super.popularMangaRequest(page)
    }

    override fun latestUpdatesRequest(page: Int): Request = if (mangaLang.isBlank()) {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment("")
            addQueryParameter("page", page.toString())
        }
        GET(url.build(), headers)
    } else {
        super.latestUpdatesRequest(page)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Basic search
        val sortOrderFilter = filters.firstInstanceOrNull<SortOrderFilter>()
        val genresFilter = filters.firstInstanceOrNull<GenresFilter>()
        val selectedGenres = genresFilter?.state?.filter { it.state } ?: emptyList()
        val favoriteFilter = filters.firstInstanceOrNull<FavoriteFilter>()

        // Speechless
        val speechlessFilter = filters.firstInstanceOrNull<SpeechlessFilter>()

        // Advanced search
        val advancedSearchFilters = filters.filterIsInstance<AdvancedTextFilter>()

        return when {
            favoriteFilter?.state == true ->
                favoriteFilterSearchRequest(page, query, filters)
            supportSpeechless && speechlessFilter?.state == true ->
                speechlessFilterSearchRequest(page, query, filters)
            supportAdvancedSearch && advancedSearchFilters.any { it.state.isNotBlank() } ->
                advancedSearchRequest(page, query, filters)
            selectedGenres.size == 1 && query.isBlank() ->
                tagBrowsingSearchRequest(page, query, filters)
            useIntermediateSearch ->
                intermediateSearchRequest(page, query, filters)
            useBasicSearch && (selectedGenres.size > 1 || query.isNotBlank()) ->
                basicSearchRequest(page, query, filters)
            sortOrderFilter?.state == 1 ->
                latestUpdatesRequest(page)
            else ->
                popularMangaRequest(page)
        }
    }

    override fun getSortOrderURIs() = listOf(
        Pair("Popular", "pp"),
        Pair("Latest", "lt"),
    )

    /**
     * Convert space( ) typed in search-box into plus(+) in URL. Then:
     * - ignore the word preceding by a special character (e.g. 'school-girl' will ignore 'girl')
     *    => replace to plus(+),
     * - use plus(+) for separate terms, as AND condition.
     * - use double quote(") to search for exact match.
     */
    override fun buildQueryString(tags: List<String>, query: String): String {
        val regexSpecialCharacters = Regex("""[^a-zA-Z0-9"]+(?=[a-zA-Z0-9"])""")
        return (tags + query + mangaLang).filterNot { it.isBlank() }.joinToString("+") {
            it.trim().replace(regexSpecialCharacters, "+")
        }
    }

    /* Details */
    override fun Element.getInfo(tag: String): String = select(".hr-gallery-entity-group:has(.hr-gallery-entity-label:contains($tag)) .hr-gallery-tag")
        .joinToString {
            val name = it.selectFirst(".hr-gallery-tag__name")?.text().orEmpty()
            if (tag.contains(regexTag)) {
                genres[name] = it.attr("href")
                    .removeSuffix("/").substringAfterLast('/')
            }
            name
        }

    override fun Element.getInfoPages(document: Document?): String? = document?.selectFirst("#js-thumbs-grid")
        ?.attr("data-total-pages")
        ?.takeIf { it.isNotBlank() }
        ?.let { "Pages: $it" }

    override fun Element.getCover() = selectFirst(".hr-gallery-cover img")?.imgAttr()

    override val mangaDetailInfoSelector = ".hr-gallery-overview"

    /* Pages */
    override val pageUri = "g"

    override fun pageListRequest(chapter: SChapter): Request {
        val galleryId = chapter.url.trimEnd('/').substringAfterLast('/')
        return GET("$baseUrl/$pageUri/$galleryId/1/", headers)
    }

    // The reader embeds the whole page list next to the first image, no need to open each page.
    override fun pageListParse(document: Document): List<Page> {
        val pages = document.selectFirst("script#readerPagesJson")?.data()
            ?: throw Exception("Failed to load page list")
        val imagesUri = document.selectFirst("img#readerImg")?.imgAttr()?.substringBeforeLast('/')
            ?: throw Exception("Failed to load page list")

        return pages.parseAs<List<ReaderPage>>().map {
            Page(it.page - 1, imageUrl = "$imagesUri/${it.page}.${it.ext}")
        }
    }

    /* Filters */
    override fun tagsParser(document: Document): List<Genre> = document.select("article.hr-taxonomy-card .hr-taxonomy-card__title a")
        .map {
            Genre(
                it.text(),
                it.attr("href")
                    .removeSuffix("/").substringAfterLast('/'),
            )
        }

    @Serializable
    private class ReaderPage(val page: Int, val ext: String)
}
