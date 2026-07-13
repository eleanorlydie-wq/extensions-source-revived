package eu.kanade.tachiyomi.extension.es.datgarscanlation

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import keiyoushi.network.rateLimit
import okhttp3.Response

class DatGarScanlation :
    ZeistManga(
        "Dat-Gar Scan",
        "https://datgarscanlation.blogspot.com",
        "es",
    ) {
    override val useNewChapterFeed = true
    override val hasFilters = true
    override val hasLanguageFilter = false

    override val client = super.client.newBuilder()
        .rateLimit(2)
        .build()

    // The homepage's Popular Posts section is populated client-side via JS
    // (mangaPost.run(...) / update.run(...) AJAX calls) with no server-rendered
    // div.PopularPosts markup, so fall back to the JSON feed used by latest updates.
    override fun popularMangaRequest(page: Int) = latestUpdatesRequest(page)
    override fun popularMangaParse(response: Response) = latestUpdatesParse(response)
}
