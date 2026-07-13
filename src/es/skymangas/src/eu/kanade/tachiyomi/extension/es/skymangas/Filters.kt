package eu.kanade.tachiyomi.extension.es.skymangas

import eu.kanade.tachiyomi.source.model.Filter

// SkyMangas was rebuilt as an Angular (SSR) app. The "Filtros" panel on
// https://skymangas.com/explorar is rendered by <sky-browse> (bundled in
// chunk-5CZWFVEO.js), whose reducer syncs a handful of these facets to and
// from the page's own query string (see the `route.queryParams.subscribe`
// block in that chunk), so the SSR HTML itself changes when these params
// are present on the request URL - no private API call is required.
//
// The canonical facet values (names + slugs/ids) were pulled from the
// public, unauthenticated https://api.skymangas.com/api/v1/manhuas/filters
// endpoint (the same one the Angular app calls to populate this panel).
//
// Note: the panel also shows "Traducción" (translationStatusId), "Año de
// publicación" (yearFrom/yearTo) and a chapter-count minimum, but live
// testing showed those three params are NOT read back into the page's
// initial state on load (`e.tab`/`e.q`/`e.sort`/`e.genres`/`e.tags`/
// `e.statusId`/`e.originationId`/`e.demographicId`/`e.author` are - the
// other three are not in that destructure), so passing them from the
// extension would silently do nothing. They are intentionally omitted here
// instead of being wired up as dead filters.

class SortFilter :
    Filter.Select<String>(
        "Ordenar",
        arrayOf("Popular", "Más reciente", "Actualizado", "Mejor valorado", "Más seguidos", "Más capítulos", "A-Z"),
    ) {
    val value: String
        get() = arrayOf("popular", "latest", "updated", "rating", "follows", "chapters", "alpha")[state]
}

class TypeFilter :
    Filter.Select<String>(
        "Tipo",
        arrayOf("Cualquiera", "Manhua", "Manhwa", "Manga", "Manfra", "OEL", "Otro"),
    )

class StatusFilter :
    Filter.Select<String>(
        "Estado",
        arrayOf("Cualquiera", "En emisión", "Completado", "En pausa", "Cancelado", "Próximamente"),
    )

class DemographicFilter :
    Filter.Select<String>(
        "Demográfica",
        arrayOf("Cualquiera", "Shounen", "Shoujo", "Seinen", "Josei", "Kodomo", "Ninguna"),
    )

class AuthorFilter : Filter.Text("Autor")

class GenreCheckBox(name: String, val slug: String) : Filter.CheckBox(name)

class GenreFilter : Filter.Group<GenreCheckBox>("Géneros", GENRES.map { (name, slug) -> GenreCheckBox(name, slug) })

class TagCheckBox(name: String, val slug: String) : Filter.CheckBox(name)

class TagFilter : Filter.Group<TagCheckBox>("Tags", TAGS.map { (name, slug) -> TagCheckBox(name, slug) })

// name to slug, verbatim from GET https://api.skymangas.com/api/v1/manhuas/filters -> data.genres
private val GENRES = listOf(
    "Acción" to "accion",
    "Artes Marciales" to "artes-marciales",
    "Aventura" to "aventura",
    "BL" to "bl",
    "Chicas Mágicas" to "chicas-magicas",
    "Comedia" to "comedia",
    "Crime" to "crime",
    "Cultivación" to "cultivacion",
    "Deportes" to "deportes",
    "Drama" to "drama",
    "Escolar" to "escolar",
    "Fantasía" to "fantasia",
    "Filosófico" to "filosofico",
    "GL" to "gl",
    "Harem" to "harem",
    "Histórico" to "historico",
    "Horror" to "horror",
    "Isekai" to "isekai",
    "Mecha" to "mecha",
    "Médico" to "medico",
    "Militar" to "militar",
    "Misterio" to "misterio",
    "Psicológico" to "psicologico",
    "Reencarnación" to "reencarnacion",
    "Romance" to "romance",
    "Sci-Fi" to "sci-fi",
    "Sistema" to "sistema",
    "Slice of Life" to "slice-of-life",
    "Sobrenatural" to "sobrenatural",
    "Superhéroes" to "superheroes",
    "Suspenso" to "suspenso",
    "Thriller" to "thriller",
    "Tragedia" to "tragedia",
    "Wuxia" to "wuxia",
    "Xianxia" to "xianxia",
    "Xuanhuan" to "xuanhuan",
)

// name to slug, verbatim from GET https://api.skymangas.com/api/v1/manhuas/filters -> data.tags
private val TAGS = listOf(
    "Full Color" to "full-color",
    "Adaptación Web Novel" to "web-novel-adaptation",
    "Long Strip" to "long-strip",
    "OP MC" to "op-mc",
    "Magic" to "magic",
    "Monsters" to "monsters",
    "Revenge" to "revenge",
    "Regresión" to "regression",
    "Viaje en el Tiempo" to "viaje-en-el-tiempo",
    "Dungeons" to "dungeons",
    "Villainess" to "villainess",
    "Gates" to "gates",
    "Harem" to "harem",
    "Murim" to "murim",
    "Necromancer" to "necromancer",
    "Romance" to "romance",
    "Sword" to "sword",
    "Tower" to "tower",
)
