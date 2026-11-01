package eu.kanade.tachiyomi.extension.ar.mangahub

import eu.kanade.tachiyomi.multisrc.madara.Madara
import keiyoushi.network.rateLimit
import java.text.SimpleDateFormat
import java.util.Locale

class MangaHub :
    Madara(
        "MangaHub",
        "https://mangaxhentai.com",
        "ar",
        SimpleDateFormat("MMMM d, yyyy", Locale("ar")),
    ) {
    override val client = super.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun popularMangaSelector() = "article.page-item-detail.manga"
    override val popularMangaUrlSelector = ".post-title a"

    override fun searchMangaSelector() = "article.c-tabs-item__content"
    override val searchMangaUrlSelector = ".post-title a"

    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true
}
