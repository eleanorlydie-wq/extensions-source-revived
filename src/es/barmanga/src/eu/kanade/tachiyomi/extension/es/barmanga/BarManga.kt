package eu.kanade.tachiyomi.extension.es.barmanga

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class BarManga :
    Madara(
        "BarManga",
        "https://archiviumbar.com",
        "es",
        SimpleDateFormat("dd/MM/yyyy", Locale.ROOT),
    ) {

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    // The site's "?m_orderby=views"/"latest"/"alphabet"/"rating" listing endpoints are currently
    // broken on the live site: they render the #loop-content grid with 16 identical placeholder
    // items (same image, empty title, href to "/"). Only the plain listing (no m_orderby, which
    // the site itself treats as its default/"latest" tab) and "?m_orderby=new-manga" return real,
    // distinct entries, so popular/latest are pointed at those instead.
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/$mangaSubString/${searchPage(page)}", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/$mangaSubString/${searchPage(page)}?m_orderby=new-manga", headers)

    // Current theme markup for the listing grid: <div id="loop-content" class="mp-grid"><a class="gba27099454" href="...">
    // <div class="ga2e0e00055"><img ...></div><span class="g560272282f">...</span>
    // <div class="g826261fa69"><span class="g64abe0cb12">Title</span>...</div></a>
    override fun popularMangaSelector() = "#loop-content > a"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst(".g64abe0cb12")?.text().orEmpty()
        element.selectFirst("img")?.let {
            thumbnail_url = processThumbnail(imageFromElement(it), true)
        }
    }

    // Live check (2026-07-13): a real manga page, e.g.
    // https://archiviumbar.com/manga/llevo-mil-anos-atrapado-en-el-mismo-dia/, still renders
    // <div class="c-breadcrumb"><ol class="breadcrumb"><li><a href=".../">Home</a></li>
    // <li><a href=".../manga-genre/...">Genre</a></li><li><a href=".../manga/slug/">Título</a></li>
    // </ol></div> so this selector is correct and unchanged.
    override val mangaDetailsSelectorTitle = ".breadcrumb > li:last-child > a"

    // The site's archive/grid endpoints (popularMangaRequest/latestUpdatesRequest above, every
    // "?m_orderby=..." variant, and the "manga-genre" taxonomy archives) are currently all broken
    // server-side: every #loop-content card renders with an empty title and
    // href="https://archiviumbar.com/" (the bare site root) instead of a real manga permalink —
    // confirmed by curling https://archiviumbar.com/manga/ directly and seeing 16 identical
    // `<a href="https://archiviumbar.com/" class="gba27099454">` cards with an empty
    // `<span class="g64abe0cb12">` title. Tapping any such entry fetches the homepage, which has
    // no ".breadcrumb" at all, so the inherited Madara#mangaDetailsParse's
    // `selectFirst(mangaDetailsSelectorTitle)!!` NPEs and crashes the app on open.
    //
    // Degrade gracefully instead of crashing: retry with the stock Madara title selectors, then
    // finally the page's always-present <title> tag/Document#title(). This is an explicit,
    // ordered fallback chain (not a single comma-joined selector) on purpose: jsoup's selectFirst
    // on an OR'd selector returns the first match in *document order*, and <title> lives in <head>
    // — before <body>'s .breadcrumb — so folding it into one selector string would make every
    // real manga page pick up the raw "<name> - BarManga" SEO title instead of the clean
    // breadcrumb text. A real manga page still hits the primary selector above via the untouched
    // super call and is completely unaffected; only a page missing it (e.g. the homepage, from a
    // still-broken listing entry) falls through to the generic fallback below.
    override fun mangaDetailsParse(document: Document): SManga = try {
        super.mangaDetailsParse(document)
    } catch (e: NullPointerException) {
        SManga.create().apply {
            title = document.selectFirst("div.post-title h3, div.post-title h1, #manga-title > h1")
                ?.ownText()
                ?.takeIf { it.isNotBlank() }
                ?: document.title()
        }
    }

    // The old admin-ajax.php nonce/token image-delivery flow is gone: chapter pages now embed
    // plain <img class="wp-manga-chapter-img" src="https://archiviumbar.com/wp-content/uploads/WP-manga/data/.../001.jpg">
    // inside <div class="page-break" data-page="1">, which the default Madara pageListParse/imageRequest already handle.
}
