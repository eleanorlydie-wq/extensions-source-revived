package eu.kanade.tachiyomi.extension.vi.truyentini

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale

class TruyenTini :
    Madara(
        "TruyenTini",
        "https://truyentini.net",
        "vi",
        SimpleDateFormat("dd/MM/yyyy", Locale("vi")),
    ) {
    override val mangaSubString = "truyen"

    override val altNameSelector = ".post-content_item:contains(Tên Khác) .summary-content"

    override val useNewChapterEndpoint = true

    // The image host answers 403 to requests that carry a referer.
    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers.newBuilder().removeAll("Referer").build())
}
