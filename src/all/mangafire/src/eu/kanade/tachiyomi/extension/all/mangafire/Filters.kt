package eu.kanade.tachiyomi.extension.all.mangafire

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UriFilter {
    fun addToUri(builder: HttpUrl.Builder)
}

open class UriPartFilter(
    name: String,
    private val param: String,
    private val vals: Array<Pair<String, String>>,
    defaultValue: String? = null,
) : Filter.Select<String>(
    name,
    vals.map { it.first }.toTypedArray(),
    vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0,
),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        builder.addQueryParameter(param, vals[state].second)
    }
}

open class UriMultiSelectOption(name: String, val value: String) : Filter.CheckBox(name)

open class UriMultiSelectFilter(
    name: String,
    private val param: String,
    private val vals: Array<Pair<String, String>>,
) : Filter.Group<UriMultiSelectOption>(name, vals.map { UriMultiSelectOption(it.first, it.second) }),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val checked = state.filter { it.state }

        checked.forEach {
            builder.addQueryParameter(param, it.value)
        }
    }
}

open class UriTriSelectOption(name: String, val value: String) : Filter.TriState(name)

open class UriTriSelectFilter(
    name: String,
    private val includeParam: String,
    private val excludeParam: String,
    private val vals: Array<Pair<String, String>>,
) : Filter.Group<UriTriSelectOption>(name, vals.map { UriTriSelectOption(it.first, it.second) }),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        state.forEach { s ->
            when (s.state) {
                TriState.STATE_INCLUDE -> builder.addQueryParameter(includeParam, s.value)
                TriState.STATE_EXCLUDE -> builder.addQueryParameter(excludeParam, s.value)
            }
        }
    }
}

class TypeFilter :
    UriMultiSelectFilter(
        "Type",
        "types[]",
        arrayOf(
            Pair("Manga", "manga"),
            Pair("Manhwa", "manhwa"),
            Pair("Manhua", "manhua"),
            Pair("Other", "other"),
        ),
    )

class ContentRatingFilter :
    UriMultiSelectFilter(
        "Content Rating",
        "content_rating[]",
        arrayOf(
            Pair("Safe", "safe"),
            Pair("Suggestive", "suggestive"),
            Pair("Erotica", "erotica"),
            Pair("Pornographic", "pornographic"),
        ),
    )

class GenreFilter :
    UriTriSelectFilter(
        "Genres",
        "genres_in[]",
        "genres_ex[]",
        arrayOf(
            Pair("Action", "1"),
            Pair("Adult", "268929"),
            Pair("Adventure", "78"),
            Pair("Avant Garde", "3"),
            Pair("Boys Love", "4"),
            Pair("Comedy", "5"),
            Pair("Crime", "268921"),
            Pair("Demons", "77"),
            Pair("Drama", "6"),
            Pair("Ecchi", "7"),
            Pair("Fantasy", "79"),
            Pair("Girls Love", "9"),
            Pair("Gourmet", "10"),
            Pair("Harem", "11"),
            Pair("Hentai", "268930"),
            Pair("Historical", "268922"),
            Pair("Horror", "530"),
            Pair("Isekai", "13"),
            Pair("Iyashikei", "531"),
            Pair("Josei", "15"),
            Pair("Kids", "532"),
            Pair("Magic", "539"),
            Pair("Magical Girls", "268923"),
            Pair("Mahou Shoujo", "533"),
            Pair("Martial Arts", "534"),
            Pair("Mature", "268931"),
            Pair("Mecha", "19"),
            Pair("Medical", "268924"),
            Pair("Military", "535"),
            Pair("Music", "21"),
            Pair("Mystery", "22"),
            Pair("Parody", "23"),
            Pair("Philosophical", "268925"),
            Pair("Psychological", "536"),
            Pair("Reverse Harem", "25"),
            Pair("Romance", "26"),
            Pair("School", "73"),
            Pair("Sci-Fi", "28"),
            Pair("Seinen", "537"),
            Pair("Shoujo", "30"),
            Pair("Shounen", "31"),
            Pair("Slice of Life", "538"),
            Pair("Smut", "268932"),
            Pair("Space", "33"),
            Pair("Sports", "34"),
            Pair("Super Power", "75"),
            Pair("Superhero", "268926"),
            Pair("Supernatural", "76"),
            Pair("Suspense", "37"),
            Pair("Thriller", "38"),
            Pair("Tragedy", "268927"),
            Pair("Vampire", "39"),
            Pair("Wuxia", "268928"),
        ),
    )

class GenreModeFilter :
    Filter.CheckBox("Must have all the selected genres"),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        if (state) {
            builder.addQueryParameter("genres_mode", "and")
        }
    }
}

class StatusFilter :
    UriMultiSelectFilter(
        "Status",
        "statuses[]",
        arrayOf(
            Pair("Releasing", "releasing"),
            Pair("Finished", "finished"),
            Pair("On Hiatus", "on_hiatus"),
            Pair("Discontinued", "discontinued"),
            Pair("Not Yet Released", "not_yet_released"),
        ),
    )

class YearFromFilter : Filter.Text("From")
class YearToFilter : Filter.Text("To")

class YearFilter :
    Filter.Group<Filter.Text>(
        "Year",
        listOf(YearFromFilter(), YearToFilter()),
    ),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        state.getOrNull(0)?.state?.trim()?.takeIf { it.isNotEmpty() }?.let {
            builder.addQueryParameter("year_from", it)
        }
        state.getOrNull(1)?.state?.trim()?.takeIf { it.isNotEmpty() }?.let {
            builder.addQueryParameter("year_to", it)
        }
    }
}

class MinChapterFilter :
    Filter.Text("Minimum chapter length"),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        if (state.isNotEmpty()) {
            val value = state.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Minimum chapter length must be a positive integer greater than 0")

            builder.addQueryParameter("min_chap", value.toString())
        }
    }
}

class SortFilter(defaultValue: String? = null) :
    Filter.Select<String>(
        "Sort",
        sortOptions.map { it.first }.toTypedArray(),
        sortOptions.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0,
    ),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val (field, direction) = sortOptions[state].second.split(":", limit = 2)
        builder.addQueryParameter("order[$field]", direction)
    }

    companion object {
        private val sortOptions = arrayOf(
            Pair("Best match", "relevance:desc"),
            Pair("Latest update", "chapter_updated_at:desc"),
            Pair("Recently added", "created_at:desc"),
            Pair("Name A-Z", "title:asc"),
            Pair("Name Z-A", "title:desc"),
            Pair("Year (newest)", "year:desc"),
            Pair("Year (oldest)", "year:asc"),
            Pair("Highest rated", "score:desc"),
            Pair("Trending", "trending:desc"),
            Pair("Most viewed - 7 days", "views_7d:desc"),
            Pair("Most viewed - 30 days", "views_30d:desc"),
            Pair("Most viewed - all time", "views_total:desc"),
            Pair("Most followed", "follows_total:desc"),
        )
    }
}
