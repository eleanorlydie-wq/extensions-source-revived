package eu.kanade.tachiyomi.extension.tr.toontaku

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second.ifEmpty { null }
}

class SortFilter :
    UriPartFilter(
        "Sıralama",
        arrayOf(
            "Popülerlik" to "totalViews,desc",
            "Yeni eklenen" to "createdAt,desc",
            "A-Z" to "title,asc",
        ),
    )

class TypeFilter :
    UriPartFilter(
        "Tür",
        arrayOf(
            "Tümü" to "",
            "Manga" to "MANGA",
            "Manhwa" to "MANHWA",
            "Manhua" to "MANHUA",
        ),
    )

class StatusFilter :
    UriPartFilter(
        "Durum",
        arrayOf(
            "Tümü" to "",
            "Devam Ediyor" to "DEVAM_EDIYOR",
            "Tamamlandı" to "TAMAMLANDI",
            "Durakladı" to "DURAKLADI",
            "Bırakıldı" to "BIRAKILDI",
        ),
    )

class OnlyFreeFilter : Filter.CheckBox("Sadece ücretsiz seriler")

class GenreCheckBox(name: String, val slug: String) : Filter.CheckBox(name)

class GenreFilter :
    Filter.Group<GenreCheckBox>(
        "Kategoriler",
        listOf(
            "Aile" to "aile",
            "Aksiyon" to "aksiyon",
            "Asil" to "asil",
            "Askeri" to "askeri",
            "Bilim Kurgu" to "bilim-kurgu",
            "Büyü" to "buyu",
            "Canavarlar" to "canavarlar",
            "Dedektif" to "dedektif",
            "Doğaüstü" to "dogaustu",
            "Dram" to "dram",
            "Dövüş Sanatları" to "dovus-sanatlari",
            "Ecchi" to "ecchi",
            "Fantastik" to "fantastik",
            "Fantezi" to "fantezi",
            "Gerilim" to "gerilim",
            "Gizem" to "gizem",
            "Günlük Hayattan Kesitler" to "gunluk-hayattan-kesitler",
            "Harem" to "harem",
            "Hayatta Kalma" to "hayatta-kalma",
            "Hayvanlar" to "hayvanlar",
            "Hentai" to "hentai",
            "Josei" to "josei",
            "Kahraman" to "kahraman",
            "Komedi" to "komedi",
            "Korku" to "korku",
            "Kraliyet" to "kraliyet",
            "Kule" to "kule",
            "Kötü Kadın" to "kotu-kadin",
            "Kıyamet Sonrası" to "kiyamet-sonrasi",
            "Macera" to "macera",
            "Mecha" to "mecha",
            "Murim" to "murim",
            "Müzik" to "muzik",
            "Ofis" to "ofis",
            "Okul" to "okul",
            "Olgun" to "olgun",
            "Oyun" to "oyun",
            "Polis" to "polis",
            "Psikolojik" to "psikolojik",
            "Reenkarnasyon" to "reenkarnasyon",
            "Regresyon" to "regresyon",
            "Romantik" to "romantik",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Sistem" to "sistem",
            "Smut" to "smut",
            "Spor" to "spor",
            "Suç" to "suc",
            "Tarihi" to "tarihi",
            "Ters Harem" to "ters-harem",
            "Trajedi" to "trajedi",
            "Tıbbi" to "tibbi",
            "Vampirler" to "vampirler",
            "Yaoi" to "yaoi",
            "Yemek" to "yemek",
            "Yetişkin" to "yetiskin",
            "Yuri" to "yuri",
            "Zaman Yolculuğu" to "zaman-yolculugu",
            "Zindan" to "zindan",
            "Zombiler" to "zombiler",
            "İblisler" to "iblisler",
            "İntikam" to "intikam",
            "İsekai" to "isekai",
        ).map { GenreCheckBox(it.first, it.second) },
    ) {
    fun toUriPart(): String? = state.filter { it.state }.joinToString(",") { it.slug }.ifEmpty { null }
}
