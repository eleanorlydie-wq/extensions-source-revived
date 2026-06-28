package eu.kanade.tachiyomi.extension.en.omegascans

import eu.kanade.tachiyomi.multisrc.heancms.HeanCms
import eu.kanade.tachiyomi.multisrc.heancms.HeanCmsGenreDto
import eu.kanade.tachiyomi.multisrc.heancms.SortByFilter
import eu.kanade.tachiyomi.multisrc.heancms.StatusFilter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.network.rateLimit
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Locale
import kotlin.concurrent.thread

class OmegaScans : HeanCms("Omega Scans", "https://omegascans.org", "en") {
    private val apiUrlHost by lazy { apiUrl.toHttpUrl().host }

    override val client = super.client.newBuilder()
        .rateLimit(1) { it.host == apiUrlHost }
        .build()

    // Site changed from MangaThemesia to HeanCms.
    override val versionId = 2

    override val useNewChapterEndpoint = true
    override val useNewQueryEndpoint = true
    override val enableLogin = true

    // ============================== Filters ==============================
    // The shared HeanCms base loads its genre checkboxes from /tags in a
    // background thread, so the filter list (built once, synchronously) almost
    // always shows the "genres missing" warning instead of any tags. Mirror the
    // e-hentai / Rule34Video extensions instead: an AutoComplete text field whose
    // suggestions exist immediately, with name->id resolution feeding the
    // tags_ids search parameter. The dictionary is seeded from a baked snapshot
    // and refreshed from /tags so it stays correct if the site adds tags.

    private class TagFilter(suggestions: List<String>) :
        Filter.AutoComplete(
            "Tags",
            "e.g. Romance, Fantasy, MILF",
            suggestions = suggestions,
        )

    // lowercase name -> id
    @Volatile
    private var tagDictionary: Map<String, Int> =
        SEED_TAGS.associate { it.first.lowercase(Locale.US) to it.second }

    // display names, alphabetically ordered
    @Volatile
    private var tagSuggestions: List<String> =
        SEED_TAGS.map { it.first }.sorted()

    // Best-effort refresh from the live /tags endpoint. Runs off-thread so it
    // never blocks building the filter list; any new tags show up the next time
    // the filter sheet is opened.
    private fun refreshTagsAsync() {
        thread {
            runCatching {
                val response = client.newCall(GET("$apiUrl/tags", headers)).execute()
                val genres = json.decodeFromString<List<HeanCmsGenreDto>>(response.body.string())
                if (genres.isNotEmpty()) {
                    tagDictionary = genres.associate { it.name.lowercase(Locale.US) to it.id }
                    tagSuggestions = genres.map { it.name }.distinct().sorted()
                }
            }
        }
    }

    override fun getFilterList(): FilterList {
        refreshTagsAsync()

        return FilterList(
            StatusFilter(intl["status_filter_title"], getStatusList()),
            SortByFilter(intl["sort_by_filter_title"], getSortProperties()),
            Filter.Separator(),
            Filter.Header("Type tag names and pick from the suggestions; separate with , or ;"),
            Filter.Header("Series must match all chosen tags. Numeric IDs also work."),
            TagFilter(tagSuggestions),
        )
    }

    // Turn the typed tag names into the numeric ids the site expects. Numeric
    // input passes through so power users can paste ids directly; names missing
    // from the cache trigger one synchronous /tags refresh before giving up.
    private fun resolveTagIds(filters: FilterList): List<Int> {
        val raw = filters.filterIsInstance<TagFilter>().firstOrNull()?.state?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()

        val tokens = raw.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val hasUnknownName = tokens.any { token ->
            token.toIntOrNull() == null && tagDictionary[token.lowercase(Locale.US)] == null
        }
        if (hasUnknownName) {
            runCatching {
                val response = client.newCall(GET("$apiUrl/tags", headers)).execute()
                val genres = json.decodeFromString<List<HeanCmsGenreDto>>(response.body.string())
                if (genres.isNotEmpty()) {
                    tagDictionary = genres.associate { it.name.lowercase(Locale.US) to it.id }
                }
            }
        }

        return tokens.mapNotNull { token ->
            token.toIntOrNull() ?: tagDictionary[token.lowercase(Locale.US)]
        }.distinct()
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sortByFilter = filters.filterIsInstance<SortByFilter>().firstOrNull()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()

        val tagIds = resolveTagIds(filters).joinToString(",", prefix = "[", postfix = "]")

        val url = "$apiUrl/query".toHttpUrl().newBuilder()
            .addQueryParameter("query_string", query)
            .addQueryParameter(if (useNewQueryEndpoint) "status" else "series_status", statusFilter?.selected?.value ?: "All")
            .addQueryParameter("order", if (sortByFilter?.state?.ascending == true) "asc" else "desc")
            .addQueryParameter("orderBy", sortByFilter?.selected ?: "total_views")
            .addQueryParameter("series_type", "Comic")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", "12")
            .addQueryParameter("tags_ids", tagIds)
            .addQueryParameter("adult", "true")

        return GET(url.build(), headers)
    }

    companion object {
        // Snapshot of https://api.omegascans.org/tags so suggestions and id
        // resolution work the instant the filter list is built. Display name to id.
        private val SEED_TAGS = listOf(
            "Drama" to 2,
            "Fantasy" to 3,
            "Harem" to 8,
            "MILF" to 16,
            "Romance" to 1,
        )
    }
}
