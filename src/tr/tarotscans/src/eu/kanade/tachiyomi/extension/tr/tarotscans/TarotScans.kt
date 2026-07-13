package eu.kanade.tachiyomi.extension.tr.tarotscans

import eu.kanade.tachiyomi.multisrc.madara.Madara

class TarotScans : Madara("Tarot Scans", "https://www.tarotscans.com", "tr") {
    override val useNewChapterEndpoint: Boolean = true
}
