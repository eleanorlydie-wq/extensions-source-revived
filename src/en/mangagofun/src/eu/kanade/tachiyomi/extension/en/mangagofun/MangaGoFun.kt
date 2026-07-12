package eu.kanade.tachiyomi.extension.en.mangagofun

import eu.kanade.tachiyomi.multisrc.madara.Madara

class MangaGoFun : Madara("MangaGo.fun", "https://www.mangago.zone", "en") {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true
}
