package eu.kanade.tachiyomi.extension.en.murimscan

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class MurimScan : ZeistManga("MurimScan", "https://www.murimscans.site", "en") {

    // Madara -> ZeistManga
    override val versionId = 2

    // The site's current custom theme ("KaiKomik") renders its homepage
    // entirely client-side (JS reads the Blogger JSON feed and injects the
    // DOM), so the static homepage HTML has no popular-post markup at all
    // (no .PopularPosts, no article/figure/figcaption elements). The
    // Blogger JSON feed itself (/feeds/posts/default/-/Series?alt=json)
    // still works and is what latestUpdatesRequest/searchMangaParse use,
    // so reuse that instead of scraping the static homepage for popular.
    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response).apply {
        mangas.forEach { it.url = it.url.substringBefore("?") }
    }

    // Details
    override val mangaDetailsSelector = "main"
    override val mangaDetailsSelectorGenres = "dl.flex:contains(Genre) a[rel=tag], dl.flex:contains(Type) a[rel=tag]"
    override val mangaDetailsSelectorInfo = "dl.flex"
    override val mangaDetailsSelectorInfoTitle = "dt"
    override val mangaDetailsSelectorInfoDescription = "dd"

    // Pages
    override val pageListSelector = ".post-body, .check-box"

    // The reader page injects its images client-side from a JSON-escaped
    // HTML blob stashed in a data-post-body attribute (e.g.
    // <div ... data-post-body='<div ...><img src=\"...\"'>),
    // never as real <img> DOM children, so pageListSelector never matches
    // anything. Decode that attribute as a JSON string literal (it uses
    // standard \u00XX / \" escapes) to recover the real <img src> tags.
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val encoded = document.selectFirst("[data-post-body]")?.attr("data-post-body")
            ?.takeIf { it.isNotBlank() }
            ?: return super.pageListParse(response)

        val html = json.decodeFromString<String>("\"$encoded\"")
        return Jsoup.parse(html, baseUrl).select("img[src]").mapIndexed { i, img ->
            Page(i, "", img.attr("abs:src"))
        }
    }

    // The chapter list container carries the series' exact Blogger label
    // in data-label (e.g. <div id='chapterlist' data-label='The Lone
    // Necromancer'>), which is what the site's own inline script uses to
    // build its feed URL. og:title includes the " - Murim Scans" site
    // suffix and never matches a real category term, which yielded 0
    // chapters.
    override fun getChapterFeedUrl(doc: Document): String {
        val label = doc.selectFirst("#chapterlist")?.attr("data-label")?.takeIf { it.isNotBlank() }
            ?: return super.getChapterFeedUrl(doc)

        return apiUrl(chapterCategory).apply {
            addPathSegment(label)
            addQueryParameter("max-results", "999999")
        }.build().toString()
    }

    override val hasFilters = true
}
