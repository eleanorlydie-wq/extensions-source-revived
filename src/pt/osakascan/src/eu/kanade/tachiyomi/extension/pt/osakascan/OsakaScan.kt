package eu.kanade.tachiyomi.extension.pt.osakascan

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import okhttp3.Request
import okhttp3.Response

class OsakaScan :
    ZeistManga(
        "Osaka Scan",
        "https://www.osakascan.com",
        "pt-BR",
    ) {
    override val client = super.client.newBuilder()
        .rateLimit(2)
        .build()

    // The homepage no longer server-renders a "Popular Posts" widget; the
    // "#trendingRow"/"#latestRow" sections are filled client-side (JS fetch)
    // from the same Blogger JSON feed used for search/latest, ranked purely
    // by localStorage view counts (nothing a stateless request can see).
    // So popular falls back to the same feed API as latest/search.
    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("h1")!!.text()
        description = document.selectFirst(mangaDetailsSelectorDescription)?.text()
        document.selectFirst("span[data-status]")?.text()?.let {
            status = parseStatus(it)
        }
        genre = document.select("dt:contains(Gênero) + dd a").joinToString { it.text() }
    }

    override fun chapterListParse(response: Response): List<SChapter> = super.chapterListParse(response)
        .map { chapter ->
            chapter.apply {
                CHAPTER_NUMBER_REGEX.find(name)?.groups?.get(0)?.value?.let {
                    chapter_number = it.toFloat()
                }
            }
        }
        .sortedBy(SChapter::chapter_number).reversed()

    override val pageListSelector = "#reader div.separator"

    companion object {
        val CHAPTER_NUMBER_REGEX = """\d+(\.\d+)?""".toRegex()
    }
}
