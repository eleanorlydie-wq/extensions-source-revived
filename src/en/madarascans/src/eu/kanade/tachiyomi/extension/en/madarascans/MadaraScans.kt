package eu.kanade.tachiyomi.extension.en.madarascans

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesiaPaidChapterHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.getPreferencesLazy
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.getValue

class MadaraScans :
    MangaThemesia(
        "Madara Scans",
        "https://madarascans.net",
        "en",
        mangaUrlDirectory = "/series",
        dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.US),
    ),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()
    private val paidChapterHelper = MangaThemesiaPaidChapterHelper(lockedChapterSelector = ".locked")

    // support for both popular/latest tabs and search
    override fun searchMangaSelector() = "div.listupd>div, div.legend-inner"

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        thumbnail_url = element.select("img").imgAttr()
        // support for both popular/latest tabs and search
        val cardTitle = element.selectFirst("h3.card-v-title > a, h3.legend-title > a")
        val titleElement = cardTitle ?: element.selectFirst("a[title]")!!
        title = cardTitle?.text() ?: titleElement.attr("title")
        setUrlWithoutDomain(titleElement.attr("href"))
    }

    // Paid chapters open a modal instead of linking to the chapter
    override fun chapterListSelector(): String = paidChapterHelper.getChapterListSelectorBasedOnHidePaidChaptersPref(
        "#chapterlist li:has(a[href])",
        preferences,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        paidChapterHelper.addHidePaidChaptersPreferenceToScreen(screen, intl)
    }

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Separator(),
            StatusFilter(intl["status_filter_title"], statusOptions),
            TypeFilter(intl["type_filter_title"], typeFilterOptions),
            OrderByFilter(intl["order_by_filter_title"], orderByFilterOptions),
        )
        if (!genrelist.isNullOrEmpty()) {
            filters.addAll(
                listOf(
                    Filter.Header(intl["genre_exclusion_warning"]),
                    GenreListFilter(intl["genre_filter_title"], getGenreList()),
                ),
            )
        } else {
            filters.add(
                Filter.Header(intl["genre_missing_warning"]),
            )
        }
        if (hasProjectPage) {
            filters.addAll(
                mutableListOf<Filter<*>>(
                    Filter.Separator(),
                    Filter.Header(intl["project_filter_warning"]),
                    Filter.Header(intl.format("project_filter_name", name)),
                    ProjectFilter(intl["project_filter_title"], projectFilterOptions),
                ),
            )
        }
        return FilterList(filters)
    }
}
