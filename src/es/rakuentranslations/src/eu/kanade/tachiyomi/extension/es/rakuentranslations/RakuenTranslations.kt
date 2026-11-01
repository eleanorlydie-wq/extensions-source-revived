package eu.kanade.tachiyomi.extension.es.rakuentranslations

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import java.text.SimpleDateFormat
import java.util.Locale

class RakuenTranslations :
    MangaThemesia(
        "Rakuen Translations",
        "https://rakuentranslations.com",
        "es",
        dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("es")),
    )
