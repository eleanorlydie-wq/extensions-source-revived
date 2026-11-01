package eu.kanade.tachiyomi.extension.th.flashmanga

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.SChapter
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class FlashManga :
    MangaThemesia(
        "Flash-Manga",
        "https://www.flash-manga.net",
        "th",
        dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("th")),
    ) {
    override fun chapterListParse(response: Response): List<SChapter> = super.chapterListParse(response).reversed()
}
