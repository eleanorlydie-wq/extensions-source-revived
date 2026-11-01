package eu.kanade.tachiyomi.extension.ar.nijitranslations

import eu.kanade.tachiyomi.multisrc.madara.Madara
import java.text.SimpleDateFormat
import java.util.Locale

class NijiTranslations :
    Madara(
        "Niji Translations",
        "https://niji-translations.com",
        "ar",
        SimpleDateFormat("MMMM d, yyyy", Locale("ar")),
    ) {
    // Paid chapters have no link to follow.
    override fun chapterListSelector() = "li.wp-manga-chapter:not(.premium-block)"
}
