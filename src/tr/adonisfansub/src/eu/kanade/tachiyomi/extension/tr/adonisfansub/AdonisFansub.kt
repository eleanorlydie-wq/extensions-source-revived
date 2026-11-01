package eu.kanade.tachiyomi.extension.tr.adonisfansub

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import java.text.SimpleDateFormat
import java.util.Locale

class AdonisFansub :
    MangaThemesia(
        "Adonis Fansub",
        "https://manga.adonisfansub.com",
        "tr",
        dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("tr")),
    )
