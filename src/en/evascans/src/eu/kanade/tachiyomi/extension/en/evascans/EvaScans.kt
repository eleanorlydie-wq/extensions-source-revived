package eu.kanade.tachiyomi.extension.en.evascans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class EvaScans :
    MangaThemesia(
        "Eva Scans",
        "https://evascans.net",
        "en",
        "/series",
        SimpleDateFormat("yyyy/MM/dd", Locale.ROOT),
    ) {
    // Fix search/listing - site uses custom card layout (div elements, not article)
    override fun searchMangaSelector() = "div.manga-card-v, .listupd .bs .bsx"

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        // Handle the custom card layout
        val titleElement = element.selectFirst("h3.card-v-title a") ?: element.selectFirst("a")
        titleElement?.let {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text()
        }
        thumbnail_url = element.selectFirst(".card-v-cover img")?.imgAttr()
            ?: element.selectFirst("img")?.imgAttr()
    }

    // Paid chapters open a modal instead of linking to the chapter
    override fun chapterListSelector(): String = "#chapterlist li:has(a[href]):not(:has(.locked-badge))"

    // Fix page reading - site uses custom reader with camelCase ID
    override val pageSelector = "div#readerArea img"
}
