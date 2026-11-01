package eu.kanade.tachiyomi.extension.id.siimanga

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.SChapter
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Siikomik :
    Madara(
        "Siikomik",
        "https://siikomik.id",
        "id",
        SimpleDateFormat("MMMM d, yyyy", Locale("id")),
    ) {
    override val versionId = 3

    override val mangaSubString = "komik"

    // The archive and search pages use a slider grid. The page URLs still work for
    // pagination, so skip the load-more detection the grid's button would trigger.
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override fun popularMangaSelector() = "div.slider__item"

    override fun searchMangaSelector() = popularMangaSelector()

    // Paid chapters that are still locked link to "#", so they can't be opened.
    override fun chapterListSelector() = "li.wp-manga-chapter:not(:has(a[href=#]))"

    override fun chapterFromElement(element: Element): SChapter = super.chapterFromElement(element).apply {
        if (element.hasClass("premium") || element.hasClass("premium-block")) {
            name = "🔒 $name"
        }
    }
}
