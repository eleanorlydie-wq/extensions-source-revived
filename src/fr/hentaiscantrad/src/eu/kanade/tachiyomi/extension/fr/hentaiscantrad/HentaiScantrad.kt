package eu.kanade.tachiyomi.extension.fr.hentaiscantrad

import eu.kanade.tachiyomi.multisrc.madara.Madara
import java.text.SimpleDateFormat
import java.util.Locale

class HentaiScantrad : Madara("Hentai-Scantrad", "https://hentai-scantrad.org", "fr", dateFormat = SimpleDateFormat("d MMMM, yyyy", Locale.FRENCH)) {
    override val mangaDetailsSelectorStatus = "div.summary-heading:contains(État) + .summary-content"
}
