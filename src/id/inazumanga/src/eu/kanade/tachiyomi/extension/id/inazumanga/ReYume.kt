package eu.kanade.tachiyomi.extension.id.inazumanga

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.Jsoup

class ReYume : ZeistManga("ReYume", "https://www.re-yume.my.id", "id") {

    // Homepage was redesigned: popular manga now lives in the "Manga" tab of the
    // popular-posts widget as <article class="pop-card"> cards, not the old
    // "#Side div.group" layout.
    override val popularMangaSelector = "#PopularPosts2 article.pop-card"
    override val popularMangaSelectorTitle = "h4 > a"
    override val popularMangaSelectorUrl = "h4 > a"

    override val mangaDetailsSelector = "#main"

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val profileManga = document.selectFirst(mangaDetailsSelector)!!
        return SManga.create().apply {
            thumbnail_url = profileManga.selectFirst("img")?.attr("abs:src")
            title = profileManga.selectFirst("h1[itemprop=name]")!!.text()
            description = profileManga.selectFirst("#synopsis")?.text()?.trim()
            genre = profileManga.select("#append-info div.col-span-2 a[rel=tag]").joinToString { it.text() }
            author = profileManga.selectFirst("#extra-info dt:contains(Author) + dd")?.text()?.trim()
            artist = profileManga.selectFirst("#extra-info dt:contains(Artist) + dd")?.text()?.trim()

            profileManga.selectFirst("#extra-info dt:contains(Alternative) + dd")?.text()?.trim()
                ?.takeIf { it.isNotBlank() && it != "-" }
                ?.let {
                    description = listOfNotNull(description?.takeIf { d -> d.isNotBlank() }, "Alternative title(s): $it")
                        .joinToString("\n\n")
                }

            val statusText = profileManga.selectFirst("#append-info dt:contains(Status) + dd")?.text()?.trim() ?: "Unknown"
            status = parseStatus(statusText)
        }
    }

    // Chapter reader images are no longer rendered into visible DOM; the real
    // <div class="separator">...<img></div> markup is stashed as escaped text
    // inside a hidden <textarea id="zeist-raw-data"> and re-injected by client JS.
    override val pageListSelector = "#zeist-raw-data"

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val rawData = document.selectFirst(pageListSelector)?.text().orEmpty()
        val content = Jsoup.parse(rawData)
        return content.select("div.separator img[src]").mapIndexed { i, img ->
            Page(i, "", img.attr("src"))
        }
    }
}
