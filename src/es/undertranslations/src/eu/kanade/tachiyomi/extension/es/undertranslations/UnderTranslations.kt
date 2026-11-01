package eu.kanade.tachiyomi.extension.es.undertranslations

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import java.text.SimpleDateFormat
import java.util.Locale

class UnderTranslations :
    MangaThemesia(
        "UnderTranslations",
        "https://undertranslations.com",
        "es",
        dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("es")),
    )
