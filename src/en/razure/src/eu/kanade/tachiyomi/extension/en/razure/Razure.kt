package eu.kanade.tachiyomi.extension.en.razure

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.FilterList

class Razure :
    MangaThemesia(
        "Razure",
        "https://razure.org",
        "en",
        "/series",
    ) {
    override fun chapterListSelector() = "#chapterlist li:not([data-num*='🔒'])"

    override fun searchMangaSelector() = ".listupd .bs .bsx:not(:has(.novelabel))"

    // Razure's catalog is now overwhelmingly novels (site's own "Type" quick-filter:
    // All/Manga/Manhwa/Manhua/Comic/Novel). Sorting by "popular" surfaces only novel
    // entries on page 1, which searchMangaSelector()'s novel exclusion then reduces to
    // zero results. Explicitly restrict the Popular/Latest requests server-side to the
    // "Manga" type (as sent by the site's own type-manga radio button) so they return
    // the site's actual manga entries instead of an empty page.
    private val mangaTypeFilter by lazy {
        TypeFilter(intl["type_filter_title"], typeFilterOptions).apply {
            state = typeFilterOptions.indexOfFirst { it.second == "Manga" }
        }
    }

    override val popularFilter by lazy {
        FilterList(OrderByFilter("", orderByFilterOptions, "popular"), mangaTypeFilter)
    }

    override val latestFilter by lazy {
        FilterList(OrderByFilter("", orderByFilterOptions, "update"), mangaTypeFilter)
    }
}
