package eu.kanade.tachiyomi.extension.it.animeshinigami

import eu.kanade.tachiyomi.multisrc.madara.Madara

class AnimeShinigami :
    Madara(
        "AnimeShinigami",
        "https://www.animeshinigami.com",
        "it",
    ) {
    override val useNewChapterEndpoint = true
}
