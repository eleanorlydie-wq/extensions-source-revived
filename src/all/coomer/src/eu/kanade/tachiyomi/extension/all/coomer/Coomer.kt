package eu.kanade.tachiyomi.extension.all.coomer

import eu.kanade.tachiyomi.multisrc.kemono.Kemono

class Coomer : Kemono("Coomer", "https://coomer.cr", "all") {
    // Coomer no longer serves original full-resolution files; only the img.* CDN
    // thumbnails load, so request those directly instead of the dead /data/ files.
    override val fullResImagesAvailable = false

    override val getTypes = listOf(
        "OnlyFans",
        "Fansly",
        "CandFans",
    )
}
