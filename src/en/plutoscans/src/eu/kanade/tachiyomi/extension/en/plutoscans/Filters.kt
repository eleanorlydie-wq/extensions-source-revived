package eu.kanade.tachiyomi.extension.en.plutoscans

import eu.kanade.tachiyomi.source.model.Filter

// All facets below were read directly from the live
// https://flamecomics.xyz/_next/data/<buildId>/browse.json payload:
// - "initialFilters": {"status": [...], "types": [...], "year": [...]}
//   (drives the site's own Status/Type/Year multi-select widgets)
// - the "categories" field present on every series entry in that same
//   payload (the site turns each of these into a "/genre/<name>" link,
//   e.g. https://flamecomics.xyz/genre/Action, which is itself served by
//   a dedicated `_next/data/<buildId>/genre/<name>.json` endpoint that
//   returns the same series-list shape filtered server-side to that genre)
// - the "Sort Order" toggle in the browse page, which just flips an
//   alphabetical (title) sort between ascending/descending.

class GenreFilter :
    Filter.Select<String>(
        "Genre (switches to /genre/<name> feed)",
        arrayOf(
            "Any",
            "Academy",
            "Action",
            "Adventure",
            "Apocalypse",
            "Betrayal",
            "Calm Protagonist",
            "Comedy",
            "Cultivation",
            "Dragons",
            "Drama",
            "Dungeons",
            "Ecchi",
            "Ecchi comedy",
            "Fantasy",
            "Fusion Fantasy",
            "Games",
            "Harem",
            "Historical",
            "Horror",
            "Hunter",
            "Isekai",
            "Josei",
            "Leveling",
            "Magic",
            "Martial Arts",
            "Mature",
            "Military",
            "Modern Fantasy",
            "Monster",
            "Murim",
            "Mystery",
            "Noir",
            "Novel",
            "Official",
            "Pokemon",
            "Post-Apocalyptic",
            "Psychological",
            "Regression",
            "Reincarnation",
            "Revenge",
            "Romance",
            "School Life",
            "Sci-fi",
            "Seinen",
            "Shoujo",
            "Shoujo Ai",
            "Shounen",
            "Slice of Life",
            "Supernatural",
            "Survival",
            "Sword and Magic",
            "Swordmaster",
            "System",
            "Thriller",
            "Time Travel",
            "Tragedy",
            "Transmigration",
            "VR",
            "Vampires",
            "Video Games",
            "Youth",
            "Zombies",
        ),
    )

class StatusCheckBox(name: String, val value: String) : Filter.CheckBox(name)

// values taken verbatim from initialFilters.status (minus the "all" sentinel)
class StatusFilter :
    Filter.Group<StatusCheckBox>(
        "Status",
        listOf(
            "cancelled" to "Cancelled",
            "coming soon" to "Coming Soon",
            "completed" to "Completed",
            "dropped" to "Dropped",
            "hiatus" to "Hiatus",
            "ongoing" to "Ongoing",
        ).map { (value, label) -> StatusCheckBox(label, value) },
    )

class TypeCheckBox(name: String) : Filter.CheckBox(name)

// values taken verbatim from initialFilters.types (minus the "all" sentinel)
class TypeFilter :
    Filter.Group<TypeCheckBox>(
        "Type",
        listOf("Comic", "Manga", "Manhua", "Manhwa", "Novel", "Russian", "Web Novel").map { TypeCheckBox(it) },
    )

class YearCheckBox(name: String) : Filter.CheckBox(name)

// values taken verbatim from initialFilters.year (minus the "all" sentinel)
class YearFilter :
    Filter.Group<YearCheckBox>(
        "Year",
        listOf(
            "2010", "2014", "2015", "2017", "2018", "2019", "2020",
            "2021", "2022", "2023", "2024", "2025", "2026",
        ).map { YearCheckBox(it) },
    )

class OrderFilter :
    Filter.Select<String>(
        "Sort Order (site's alphabetical toggle)",
        arrayOf("Default (unsorted)", "Title: A-Z", "Title: Z-A"),
    )
