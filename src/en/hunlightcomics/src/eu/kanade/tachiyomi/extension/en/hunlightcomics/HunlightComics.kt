package eu.kanade.tachiyomi.extension.en.hunlightcomics

import eu.kanade.tachiyomi.multisrc.madara.Madara

class HunlightComics :
    Madara(
        "Hunlight Comics",
        "https://hunlightcomics.com",
        "en",
    ) {
    override val useNewChapterEndpoint = true
}
