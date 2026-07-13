package eu.kanade.tachiyomi.extension.es.lectormangalat

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun selectedValue() = vals[state].second
}

class SortFilter :
    UriPartFilter(
        "Ordenar por",
        arrayOf(
            "Predeterminado" to "",
            "Nombre" to "name",
            "Calificación" to "rating",
            "Número de capítulos" to "chapter_count",
            "Favoritos" to "bookmark_count",
            "Vistas" to "view_count",
        ),
    )

class OrderFilter :
    UriPartFilter(
        "Orden",
        arrayOf(
            "Descendente" to "desc",
            "Ascendente" to "asc",
        ),
    )

class StatusFilter :
    UriPartFilter(
        "Estado",
        arrayOf(
            "Todos" to "",
            "Cancelada" to "Cancelada",
            "En emisión" to "En emisión",
            "Finalizado" to "Finalizado",
            "Hiatus" to "Hiatus",
        ),
    )

class GenreFilter :
    UriPartFilter(
        "Género",
        arrayOf(
            "Todos" to "",
            "+18" to "+18",
            "Accion" to "accion",
            "Adulto" to "adulto",
            "Apocalíptico" to "apocalíptico",
            "Artes Marciales" to "artes-marciales",
            "Aventura" to "aventura",
            "Boys Love" to "boys-love",
            "Ciencia ficción" to "ciencia-ficción",
            "Comedia" to "comedia",
            "Demonios" to "demonios",
            "Deporte" to "deporte",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Familia" to "familia",
            "Fantasía" to "fantasía",
            "Girls Love" to "girls-love",
            "Gore" to "gore",
            "Harem" to "harem",
            "Harem Inverso" to "harem-inverso",
            "Histórico" to "histórico",
            "Horror" to "horror",
            "Josei" to "josei",
            "Maduro" to "maduro",
            "Magia" to "magia",
            "Militar" to "militar",
            "Misterio" to "misterio",
            "Psicológico" to "psicológico",
            "Recuentos de la vida" to "recuentos-de-la-vida",
            "Reencarnación" to "reencarnación",
            "Regresion" to "regresion",
            "Romance" to "romance",
            "Seinen" to "seinen",
            "Shonen" to "shonen",
            "Shoujo" to "shoujo",
            "Shoujo Ai" to "shoujo-ai",
            "Shounen Ai" to "shounen-ai",
            "Smut" to "smut",
            "Sobrenatural" to "sobrenatural",
            "Supervivencia" to "supervivencia",
            "Tragedia" to "tragedia",
            "Transmigración" to "transmigración",
            "Vida Escolar" to "vida-escolar",
        ),
    )
