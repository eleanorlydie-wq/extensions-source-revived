package eu.kanade.tachiyomi.extension.pt.galaxscanlator

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import okhttp3.Response
import org.jsoup.Jsoup
import kotlin.time.Duration.Companion.seconds

class GalaxScanlator :
    ZeistManga(
        "GALAX Scans",
        "https://galaxscanlator.blogspot.com",
        "pt-BR",
    ) {
    override val client = super.client.newBuilder()
        .rateLimit(5, 2.seconds)
        .build()

    override val popularMangaSelector = "#PopularPosts2 article"
    override val popularMangaSelectorTitle = "h4"
    override val popularMangaSelectorUrl = "a"

    override val mangaDetailsSelector = ".grid.gta-series"
    override val mangaDetailsSelectorGenres = "dt:contains(Gênero) + dd a[rel=tag]"

    override val useNewChapterFeed = true
    override val chapterCategory = "Chapter"
    override val pageListSelector = "#reader"

    // Newer ZeistManga "Zero Preload" reader stores the chapter's raw HTML
    // (unencrypted for non-locked chapters) inside a hidden textarea instead
    // of rendering <img> tags directly, so the images must be re-parsed
    // out of that raw text instead of queried straight from the document.
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val rawData = document.selectFirst("#zeist-raw-data")?.text()

        if (!rawData.isNullOrBlank() && rawData.contains("<img")) {
            val fragment = Jsoup.parseBodyFragment(rawData, document.location())
            return fragment.select("img[src]").mapIndexed { i, img ->
                Page(i, "", img.attr("abs:src"))
            }
        }

        return document.select(pageListSelector).select("img[src]").mapIndexed { i, img ->
            Page(i, "", img.attr("abs:src"))
        }
    }

    override val hasFilters = true
    override val hasLanguageFilter = false
}
