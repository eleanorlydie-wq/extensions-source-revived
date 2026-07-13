package eu.kanade.tachiyomi.extension.vi.mehentai

import eu.kanade.tachiyomi.multisrc.manhwaz.ManhwaZ

class MeHentai :
    ManhwaZ(
        "MeHentai",
        "https://mehentai.blog",
        "vi",
        mangaDetailsAuthorHeading = "Tác giả",
        mangaDetailsStatusHeading = "Trạng thái",
    ) {
    override val searchPath = "tim-kiem"

    // Site's homepage no longer uses "#slide-top"; the "TRUYỆN HOT" popular
    // section is now rendered as <div class="slide-home ..."><div class="row">
    // <div class="item col-4 col-md-2">...
    override fun popularMangaSelector() = ".slide-home .item"
}
