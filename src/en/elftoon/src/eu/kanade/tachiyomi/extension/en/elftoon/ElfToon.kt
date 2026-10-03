package eu.kanade.tachiyomi.extension.en.elftoon

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia

class ElfToon : MangaThemesia("Elf Toon", "https://elftoon.net", "en") {

    override fun chapterListSelector() = "#chapterlist li:not(:has(.gem-price-icon))"
}
