package eu.kanade.tachiyomi.extension.en.hadesscans

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesiaPaidChapterHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class HadesScans :
    MangaThemesia(
        name = "Hades Scans",
        baseUrl = "https://hadesscans.com",
        lang = "en",
        dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH),
    ),
    ConfigurableSource {

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(2)
        .build()

    private val preferences: SharedPreferences = getPreferences()

    private val paidChapterHelper = MangaThemesiaPaidChapterHelper(lockedChapterSelector = ".locked-badge")

    // Site was rebuilt on a bespoke "cx-theme" (wp-child-theme-cx-theme) WordPress theme, so the
    // stock MangaThemesia selectors no longer match anything. Selectors below were confirmed
    // against the live HTML of https://hadesscans.com/manga/ and a series/chapter page.
    private val chapterItemSelector = "a.cx-chapter-item"

    override fun chapterListSelector(): String = paidChapterHelper.getChapterListSelectorBasedOnHidePaidChaptersPref(
        chapterItemSelector,
        preferences,
    )

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.select(".cx-chapter-item__title").text().ifBlank { element.text() }
        date_upload = element.selectFirst(".cx-chapter-item__date")?.attr("datetime")?.let { parseIsoDate(it) } ?: 0L
    }

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH)

    private fun parseIsoDate(date: String): Long = try {
        isoDateFormat.parse(date)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    override fun searchMangaSelector() = ".cx-poster-card"

    override fun searchMangaFromElement(element: Element): SManga = super.searchMangaFromElement(element).apply {
        title = element.select("h3.cx-poster-card__title").text()
    }

    override fun searchMangaNextPageSelector(): String? = "nav.cx-pagination a.next"

    override val seriesDetailsSelector = ".cx-single-manga__container"
    override val seriesTitleSelector = ".cx-single-hero__title"
    override val seriesAltNameSelector = ".cx-single-hero__alt"
    override val seriesAuthorSelector = ".cx-single-hero__info-item:has(.cx-single-hero__info-label:contains(Author)) .cx-single-hero__info-value"
    override val seriesArtistSelector = ".cx-single-hero__info-item:has(.cx-single-hero__info-label:contains(Artist)) .cx-single-hero__info-value"
    override val seriesDescriptionSelector = ".cx-single-synopsis__body"
    override val seriesGenreSelector = ".cx-single-hero__genre-list a"
    override val seriesStatusSelector = ".cx-single-hero__info-item:has(.cx-single-hero__info-label:contains(Status)) .cx-single-hero__info-value"
    override val seriesThumbnailSelector = ".cx-single-hero__cover img"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        paidChapterHelper.addHidePaidChaptersPreferenceToScreen(
            screen,
            intl,
        )
    }
}
