package eu.kanade.tachiyomi.extension.ar.comicverse

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import okhttp3.Response
import org.jsoup.nodes.Document

class ComicVerse : ZeistManga("Comic Verse", "https://arcomixverse.blogspot.com", "ar") {

    override val pageListSelector = "#cvOriginal div.separator"

    // The homepage is a custom layout without the theme's popular grid; use the series feed instead.
    override fun popularMangaRequest(page: Int) = latestUpdatesRequest(page)
    override fun popularMangaParse(response: Response) = latestUpdatesParse(response)

    override fun getChapterFeedUrl(doc: Document): String {
        val widget = doc.selectFirst("div.manga-widget[data-label]")
            ?: throw Exception("Failed to find chapter feed")

        return apiUrl(chapterCategory)
            .addPathSegment(widget.attr("data-label"))
            .build().toString()
    }
}
