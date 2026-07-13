package eu.kanade.tachiyomi.extension.es.emperorscan

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class EmperorScan :
    Madara(
        "Emperor Scan",
        "https://imperiomanhua.com",
        "es",
        SimpleDateFormat("MMMM dd, yyyy", Locale("es")),
    ),
    ConfigurableSource {
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true

    override val client = super.client.newBuilder()
        .rateLimit(2) { it.host == baseUrlHost }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .setRandomUserAgent()

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override val mangaDetailsSelectorDescription = "div.syn, div.summary_content div.post-content_item:has(h5:contains(Sinopsis)) div"

    // The manga detail page was also redesigned with the same custom child
    // theme (a "hero" block) that replaced Madara's default profile-manga
    // markup, so the stock `div.post-title h3/h1` title selector (used
    // behind a `!!` in Madara.mangaDetailsParse) never matches and throws
    // a NullPointerException the instant a title is opened. Point every
    // detail selector at the real hero markup, e.g.:
    //   <div class="hcol"><h1 class="htitle">Title</h1>
    //     <div class="htags"><span class="htag htag--status">En Curso</span></div>
    //     <div class="hchips hchips--genres"><a class="chip" href=...>Genre</a></div>
    //   <div class="hposter"><div class="hposter__card"><img ... /></div></div>
    //   <div class="syn clamp" id="syn"><p>Synopsis text…</p></div>
    override val mangaDetailsSelectorTitle = "div.hcol h1.htitle, div.post-title h3, div.post-title h1, #manga-title > h1"
    override val mangaDetailsSelectorThumbnail = "div.hposter img, div.summary_image img"
    override val mangaDetailsSelectorStatus = "div.htags span.htag--status, div.summary-content, div.summary-heading:contains(Status) + div"
    override val mangaDetailsSelectorGenre = "div.hchips--genres a.chip, div.genres-content a"

    // The site's archive/search listing was redesigned with a custom child
    // theme markup (`a.acard` cards) that replaced Madara's default
    // `div.page-item-detail` structure. Manga detail pages and the ajax
    // chapter list still use the stock Madara markup.
    override fun popularMangaSelector() = "a.acard"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.attr("abs:href"))
        manga.title = element.attr("title")
        element.selectFirst("img.ac-cover")?.let {
            manga.thumbnail_url = it.attr("abs:src")
        }
        return manga
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    // The child theme dropped the `div#manga-chapters-holder` wrapper that
    // Madara.chapterListParse relies on to decide whether to call the AJAX
    // chapter endpoint, so the base implementation never fires that request.
    // `$mangaUrl/ajax/chapters` (POST) still returns the standard
    // `li.wp-manga-chapter` markup, so fetch it unconditionally instead.
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        var chapterElements = document.select(chapterListSelector())
        if (chapterElements.isEmpty()) {
            val mangaUrl = document.location().removeSuffix("/")
            val xhrResponse = client.newCall(xhrChaptersRequest(mangaUrl)).execute()
            chapterElements = xhrResponse.asJsoup().select(chapterListSelector())
            xhrResponse.close()
        }

        return chapterElements.map(::chapterFromElement)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }
}
