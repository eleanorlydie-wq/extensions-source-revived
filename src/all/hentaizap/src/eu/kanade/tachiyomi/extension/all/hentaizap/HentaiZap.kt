package eu.kanade.tachiyomi.extension.all.hentaizap

import eu.kanade.tachiyomi.multisrc.galleryadults.CategoryFilters
import eu.kanade.tachiyomi.multisrc.galleryadults.FavoriteFilter
import eu.kanade.tachiyomi.multisrc.galleryadults.GalleryAdults
import eu.kanade.tachiyomi.multisrc.galleryadults.Genre
import eu.kanade.tachiyomi.multisrc.galleryadults.GenresFilter
import eu.kanade.tachiyomi.multisrc.galleryadults.RandomEntryFilter
import eu.kanade.tachiyomi.multisrc.galleryadults.SortOrderFilter
import eu.kanade.tachiyomi.multisrc.galleryadults.SpeechlessFilter
import eu.kanade.tachiyomi.multisrc.galleryadults.imgAttr
import eu.kanade.tachiyomi.multisrc.galleryadults.toBinary
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
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
import kotlin.collections.set

class HentaiZap(
    lang: String = "all",
    override val mangaLang: String = LANGUAGE_MULTI,
) : GalleryAdults(
    "HentaiZap",
    "https://hentaizap.com",
    lang = lang,
) {
    override val supportsLatest = true
    override val supportSpeechless: Boolean = true

    override fun popularMangaSelector() = "article.hz-gallery-card"

    override fun popularMangaNextPageSelector() = "a[rel=next]"

    override fun Element.mangaUrl() = selectFirst(".hz-gallery-card__cover")?.attr("abs:href")

    override fun Element.mangaThumbnail() = selectFirst(".hz-gallery-card__media img")?.imgAttr()

    override fun Element.mangaTitle(selector: String): String? = mangaFullTitle(selector.takeIf { it != ".caption" } ?: ".hz-gallery-card__title").let {
        if (preferences.shortTitle) it?.shortenTitle() else it
    }

    override fun Element.mangaLang() = selectFirst(".hz-gallery-card__flag")?.attr("href")
        .orEmpty()
        .removeSuffix("/")
        .substringAfterLast("/")
        .let {
            // Include Speechless in search results
            if (it == LANGUAGE_SPEECHLESS) mangaLang else it
        }

    /* Popular */
    override fun popularMangaRequest(page: Int): Request = if (mangaLang.isBlank()) {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("popular")
            addPageUri(page)
        }
        GET(url.build(), headers)
    } else {
        super.popularMangaRequest(page)
    }

    /* Search */
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Basic search
        val genresFilter = filters.firstInstanceOrNull<GenresFilter>()
        val selectedGenres = genresFilter?.state?.filter { it.state } ?: emptyList()
        val favoriteFilter = filters.firstInstanceOrNull<FavoriteFilter>()

        // Speechless
        val speechlessFilter = filters.firstInstanceOrNull<SpeechlessFilter>()

        return when {
            favoriteFilter?.state == true ->
                favoriteFilterSearchRequest(page, query, filters)
            supportSpeechless && speechlessFilter?.state == true ->
                speechlessFilterSearchRequest(page, query, filters)
            selectedGenres.size == 1 && query.isBlank() ->
                tagBrowsingSearchRequest(page, query, filters)
            useBasicSearch && (selectedGenres.size > 1 || query.isNotBlank()) ->
                basicSearchRequest(page, query, filters)
            else ->
                browsingWithFilters(page, filters)
        }
    }

    override val basicSearchKey = "key"

    /**
     * This supports filter query search with languages, categories (manga, doujinshi...)
     * with additional sort orders.
     */
    private fun browsingWithFilters(page: Int, filters: FilterList): Request {
        // Basic search
        val sortOrderFilter = filters.firstInstanceOrNull<SortOrderFilter>()

        // Intermediate search
        val categoryFilters = filters.firstInstanceOrNull<CategoryFilters>()

        // Only for query string or multiple tags
        val url = "$baseUrl/search/".toHttpUrl().newBuilder().apply {
            addQueryParameter("filter", "yes")
            getSortOrderURIs().forEachIndexed { index, pair ->
                addQueryParameter(pair.second, toBinary(sortOrderFilter?.state == index))
            }
            categoryFilters?.state?.forEach {
                addQueryParameter(it.uri, toBinary(it.state))
            }
            getLanguageURIs().forEach { pair ->
                addQueryParameter(
                    pair.second,
                    toBinary(mangaLang == pair.first || mangaLang == LANGUAGE_MULTI),
                )
            }
            addPageUri(page)
        }
        return GET(url.build(), headers)
    }

    override val favoritePath = "inc/user.php?act=favs"

    /* Details */
    override val mangaDetailInfoSelector = ".hz-gallery-overview"

    override fun Element.getCover() = selectFirst(".hz-gallery-cover img")?.imgAttr()

    override fun Element.getInfo(tag: String): String = select(".hz-gallery-entity-group:has(.hz-gallery-entity-label:contains($tag)) .hz-gallery-tag")
        .joinToString {
            val name = it.selectFirst(".hz-gallery-tag__name")?.text().orEmpty()
            if (tag.contains(regexTag)) {
                genres[name] = it.attr("href")
                    .removeSuffix("/").substringAfterLast('/')
            }
            name
        }

    override fun Element.getInfoPages(document: Document?): String? = document?.selectFirst(".hz-gallery-thumbs")
        ?.attr("data-total-pages")
        ?.takeIf { it.isNotBlank() }
        ?.let { "Pages: $it" }

    /* Pages */
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

    override fun tagsParser(document: Document): List<Genre> = document.select("article.hz-taxonomy-card .hz-taxonomy-card__title a")
        .map {
            Genre(
                it.text(),
                it.attr("href")
                    .removeSuffix("/").substringAfterLast('/'),
            )
        }

    override fun getFilterList(): FilterList {
        requestTags()
        val filters = emptyList<Filter<*>>().toMutableList()

        filters.add(Filter.Header("HINT: Separate search term with comma (,)"))

        if (genres.isEmpty()) {
            filters.add(Filter.Header("Press 'reset' to attempt to load tags"))
        } else {
            filters.add(GenresFilter(genres))
        }

        filters.add(SortOrderFilter(getSortOrderURIs()))
        filters.add(Filter.Header("String query search doesn't support Sort"))

        filters.add(CategoryFilters(getCategoryURIs()))

        filters.add(Filter.Separator())
        filters.add(SpeechlessFilter())
        filters.add(FavoriteFilter())
        filters.add(RandomEntryFilter())

        return FilterList(filters)
    }

    override fun getSortOrderURIs() = listOf(
        Pair("Popular", "pp"),
        Pair("Latest", "lt"),
        Pair("Downloads", "dl"),
        Pair("Top Rated", "tr"),
    )

    override fun relatedMangaSelector() = ".hz-gallery-related ${popularMangaSelector()}"

    @Serializable
    private class ReaderPage(val page: Int, val ext: String)
}
