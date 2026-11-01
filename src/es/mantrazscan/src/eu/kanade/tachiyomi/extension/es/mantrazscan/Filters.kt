package eu.kanade.tachiyomi.extension.es.mantrazscan

import eu.kanade.tachiyomi.source.model.Filter

class GenreFilter : Filter.Select<String>("Género", GENRES.map { it.first }.toTypedArray()) {
    fun toUriPart() = GENRES[state].second
}

private val GENRES = arrayOf(
    "Todos" to "",
    "Romance" to "romance",
    "Drama" to "drama",
    "Fantasía" to "fantasia",
    "Comedia" to "comedia",
    "Acción" to "accion",
    "Aventura" to "aventura",
    "Harem" to "harem",
    "Isekai" to "isekai",
    "Manhwa" to "manhwa",
    "Manga" to "manga",
    "Manhua" to "manhua",
    "Shounen" to "shounen",
    "Seinen" to "seinen",
    "BL" to "bl",
    "Yaoi" to "yaoi",
    "Yuri" to "yuri",
    "+18" to "18",
    "Sin censura" to "sin-censura",
)
