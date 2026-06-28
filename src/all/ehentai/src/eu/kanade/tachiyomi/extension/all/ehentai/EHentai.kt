package eu.kanade.tachiyomi.extension.all.ehentai

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Filter.CheckBox
import eu.kanade.tachiyomi.source.model.Filter.Select
import eu.kanade.tachiyomi.source.model.Filter.Text
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

abstract class EHentai(
    override val lang: String,
    private val ehLang: String,
) : HttpSource(),
    ConfigurableSource {
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val name = "E-Hentai"

    override val baseUrl = "https://e-hentai.org"

    override val supportsLatest = true

    private var lastMangaId = ""

    // true if lang is a "natural human language"
    private fun isLangNatural(): Boolean = lang !in listOf("none", "other")

    private fun genericMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangaElements =
            doc
                .select("table.itg td.glname")
                .let { elements ->
                    if (isLangNatural() && getEnforceLanguagePref()) {
                        elements.filter { element ->
                            // only accept elements with a language tag matching ehLang or without a language tag
                            // could make this stricter and not accept elements without a language tag, possibly add a sharedpreference for it
                            element.select("div[title^=language]").firstOrNull()?.let { it.text() == ehLang } ?: true
                        }
                    } else {
                        elements
                    }
                }
        val parsedMangas: MutableList<SManga> = mutableListOf()
        for (i in mangaElements.indices) {
            val manga =
                mangaElements[i].let {
                    SManga.create().apply {
                        // Get title
                        it.select("a")?.first()?.apply {
                            title = this.select(".glink").text()
                            url = ExGalleryMetadata.normalizeUrl(attr("href"))
                            if (i == mangaElements.lastIndex) {
                                lastMangaId = ExGalleryMetadata.galleryId(attr("href"))
                            }
                        }
                        // Get image
                        it.parent()?.select(".glthumb img")?.first().apply {
                            thumbnail_url = this?.attr("data-src")?.nullIfBlank()
                                ?: this?.attr("src")
                        }
                    }
                }
            parsedMangas.add(manga)
        }

        // Add to page if required
        val hasNextPage = doc.select("a#unext[href]").hasText()

        return MangasPage(parsedMangas, hasNextPage)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.just(
        listOf(
            SChapter.create().apply {
                url = manga.url
                name = "Chapter"
                chapter_number = 1f
            },
        ),
    )

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        // chapter.url can carry a "?nw=always" query (added by ExGalleryMetadata.normalizeUrl).
        // E-Hentai IGNORES the ?p= thumbnail-pagination param whenever nw=always is present: every
        // ?p=N then returns the FIRST thumbnails page, so the page list repeats page 1's thumbnails
        // (e.g. the same 20 pages over and over) and never reaches the later pages. The nw cookie
        // already bypasses the content warning, so drop the query before paginating.
        val baseChapterUrl = "$baseUrl/${chapter.url.trimStart('/')}".substringBefore('?')
        return chapterPageCall(baseChapterUrl).flatMap { response ->
            val doc = response.asJsoup()
            val firstImages = parseChapterPage(doc)
            val maxPageIndex = maxChapterPageIndex(doc)
            if (maxPageIndex == 0) {
                Observable.just(firstImages)
            } else {
                Observable
                    .from((1..maxPageIndex).toList())
                    .flatMap(
                        { p ->
                            chapterPageCall(addParam(baseChapterUrl, "p", p.toString()))
                                .map { p to parseChapterPage(it.asJsoup()) }
                        },
                        PAGE_FETCH_CONCURRENCY,
                    ).toList()
                    .map { partials ->
                        firstImages + partials.sortedBy { it.first }.flatMap { it.second }
                    }
            }
        }.map {
            it.mapIndexed { i, s ->
                Page(i, s)
            }
        }
    }

    private fun parseChapterPage(response: Element) = with(response) {
        // E-Hentai's thumbnail layout: <div id="gdt"> ... <a href=".../s/<token>/<gid>-<page>">
        //   <div title="Page N: filename" style="background:url(thumb)"></div></a>
        // (the old ".gdtm a" + child <img alt="N"> markup no longer exists). Derive the page
        // number from the trailing "-<page>" of the /s/ link, falling back to the div title.
        select("#gdt a")
            .map { element ->
                val href = element.attr("href")
                val pageNumber = href.substringAfterLast('-').toIntOrNull()
                    ?: element.selectFirst("div[title]")
                        ?.attr("title")
                        ?.substringAfter("Page ", "")
                        ?.substringBefore(':')
                        ?.trim()
                        ?.toIntOrNull()
                    ?: 0
                Pair(pageNumber, href)
            }.sortedBy(Pair<Int, String>::first)
            .map { it.second }
    }

    /**
     * Determine the highest 0-based thumbnail-page index from the gallery pagination table.
     */
    private fun maxChapterPageIndex(element: Element): Int = (
        element
            .select(".ptt td a")
            .mapNotNull { it.text().trim().toIntOrNull() }
            .maxOrNull() ?: 1
        ) - 1

    private fun chapterPageCall(np: String) = client.newCall(chapterPageRequest(np)).asObservableSuccess()

    private fun chapterPageRequest(np: String) = exGet(np, null, headers)

    private fun languageTag(enforceLanguageFilter: Boolean = false): String = if (enforceLanguageFilter || getEnforceLanguagePref()) "language:$ehLang" else ""

    override fun popularMangaRequest(page: Int) = if (isLangNatural()) {
        exGet("$baseUrl/?f_search=${languageTag()}&f_srdd=5&f_sr=on", page)
    } else {
        latestUpdatesRequest(page)
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val enforceLanguageFilter = filters.find { it is EnforceLanguageFilter }?.state == true
        val uri = Uri.parse("$baseUrl$QUERY_PREFIX").buildUpon()
        var modifiedQuery =
            when {
                !isLangNatural() -> query
                query.isBlank() -> languageTag(enforceLanguageFilter)
                else -> languageTag(enforceLanguageFilter).let { if (it.isNotEmpty()) "$query,$it" else query }
            }
        val typedTags =
            (
                filters.filterIsInstance<NamespaceTagFilter>().flatMap { it.qualifiedTags() } +
                    filters.filterIsInstance<OtherTagFilter>().flatMap { it.qualifiedTags() }
                ).distinct()
        // Remember how often each tag is searched so frequent ones are proposed first next time.
        recordTagUsage(typedTags)
        modifiedQuery +=
            typedTags
                .joinToString(",")
                .let { if (it.isNotEmpty()) ",$it" else it }
        uri.appendQueryParameter("f_search", modifiedQuery)
        // when attempting to search with no genres selected, will auto select all genres
        filters.filterIsInstance<GenreGroup>().firstOrNull()?.state?.let {
            // variable to to check is any genres are selected
            val check = it.any { option -> option.state } // or it.any(GenreOption::state)
            // if no genres are selected by the user set all genres to on
            if (!check) {
                for (i in it) {
                    i.state = true
                }
            }
        }

        filters.forEach {
            if (it is UriFilter) it.addToUri(uri)
        }

        if (uri.toString().contains("f_spf") || uri.toString().contains("f_spt")) {
            if (page > 1) uri.appendQueryParameter("from", lastMangaId)
        }

        return exGet(uri.toString(), page)
    }

    override fun latestUpdatesRequest(page: Int) = exGet(baseUrl, page)

    override fun popularMangaParse(response: Response) = genericMangaParse(response)

    override fun searchMangaParse(response: Response) = genericMangaParse(response)

    override fun latestUpdatesParse(response: Response) = genericMangaParse(response)

    private fun exGet(
        url: String,
        page: Int? = null,
        additionalHeaders: Headers? = null,
        cache: Boolean = true,
    ): Request {
        // pages no longer exist, if app attempts to go to the first page after a request, do not include the page append
        val pageIndex = if (page == 1) null else page
        return GET(
            pageIndex?.let {
                addParam(url, "next", lastMangaId)
            } ?: url,
            additionalHeaders?.let { header ->
                val headers = headers.newBuilder()
                header.toMultimap().forEach { (t, u) ->
                    u.forEach {
                        headers.add(t, it)
                    }
                }
                headers.build()
            } ?: headers,
        ).let {
            if (!cache) {
                it.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()
            } else {
                it
            }
        }
    }

    /**
     * Parse gallery page to metadata model
     */
    @SuppressLint("DefaultLocale")
    override fun mangaDetailsParse(response: Response) = with(response.asJsoup()) {
        with(ExGalleryMetadata()) {
            url = response.request.url.encodedPath
            title = select("#gn").text().nullIfBlank()?.trim()

            altTitle = select("#gj").text().nullIfBlank()?.trim()

            // Thumbnail is set as background of element in style attribute
            thumbnailUrl =
                select("#gd1 div").attr("style").nullIfBlank()?.let {
                    it.substring(it.indexOf('(') + 1 until it.lastIndexOf(')'))
                }
            genre =
                select("#gdc div")
                    .text()
                    .nullIfBlank()
                    ?.trim()
                    ?.lowercase()

            uploader = select("#gdn").text().nullIfBlank()?.trim()

            // Parse the table
            select("#gdd tr").forEach {
                it
                    .select(".gdt1")
                    .text()
                    .nullIfBlank()
                    ?.trim()
                    ?.let { left ->
                        it
                            .select(".gdt2")
                            .text()
                            .nullIfBlank()
                            ?.trim()
                            ?.let { right ->
                                ignore {
                                    when (
                                        left
                                            .removeSuffix(":")
                                            .lowercase()
                                    ) {
                                        "posted" -> {
                                            datePosted = EX_DATE_FORMAT.parse(right)?.time ?: 0
                                        }
                                        "visible" -> {
                                            visible = right.nullIfBlank()
                                        }
                                        "language" -> {
                                            language = right.removeSuffix(TR_SUFFIX).trim().nullIfBlank()
                                            translated = right.endsWith(TR_SUFFIX, true)
                                        }
                                        "file size" -> {
                                            size = parseHumanReadableByteCount(right)?.toLong()
                                        }
                                        "length" -> {
                                            length =
                                                right
                                                    .removeSuffix("pages")
                                                    .trim()
                                                    .nullIfBlank()
                                                    ?.toInt()
                                        }
                                        "favorited" -> {
                                            favorites =
                                                right
                                                    .removeSuffix("times")
                                                    .trim()
                                                    .nullIfBlank()
                                                    ?.toInt()
                                        }
                                    }
                                }
                            }
                    }
            }

            // Parse ratings
            ignore {
                averageRating =
                    select("#rating_label")
                        .text()
                        .removePrefix("Average:")
                        .trim()
                        .nullIfBlank()
                        ?.toDouble()
                ratingCount =
                    select("#rating_count")
                        .text()
                        .trim()
                        .nullIfBlank()
                        ?.toInt()
            }

            // Parse tags
            tags.clear()
            select("#taglist tr").forEach {
                val namespace = it.select(".tc").text().removeSuffix(":")
                val currentTags =
                    it.select("div").map { element ->
                        Tag(
                            element.text().trim(),
                            element.hasClass("gtl"),
                        )
                    }
                tags[namespace] = currentTags
            }

            // Copy metadata to manga
            SManga.create().apply {
                copyTo(this)
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }
    }

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/g/$id", headers)

    private fun searchMangaByIdParse(
        response: Response,
        id: String,
    ): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = "/g/$id/"
        return MangasPage(listOf(details), false)
    }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> = if (query.startsWith(PREFIX_ID_SEARCH)) {
        val id = query.removePrefix(PREFIX_ID_SEARCH)
        client
            .newCall(searchMangaByIdRequest(id))
            .asObservableSuccess()
            .map { response -> searchMangaByIdParse(response, id) }
    } else {
        super.fetchSearchManga(page, query, filters)
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException("Unused method was called somehow!")

    override fun pageListParse(response: Response) = throw UnsupportedOperationException("Unused method was called somehow!")

    override fun imageUrlParse(response: Response): String = response.asJsoup().select("#img").attr("abs:src")

    private val cookiesHeader by lazy {
        val cookies = mutableMapOf<String, String>()

        // Setup settings
        val settings = mutableListOf<String>()

        // Do not show popular right now pane as we can't parse it
        settings += "prn_n"

        // Exclude every other language except the one we have selected
        settings += "xl_" +
            languageMappings
                .filter { it.first != ehLang }
                .flatMap { it.second }
                .joinToString("x")

        cookies["uconfig"] = buildSettings(settings)

        // Bypass "Offensive For Everyone" content warning
        cookies["nw"] = "1"

        buildCookies(cookies)
    }

    // Headers
    override fun headersBuilder() = super.headersBuilder().add("Cookie", cookiesHeader)

    private fun buildSettings(settings: List<String?>) = settings.filterNotNull().joinToString(separator = "-")

    private fun buildCookies(cookies: Map<String, String>) = cookies.entries.joinToString(separator = "; ", postfix = ";") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    @Suppress("SameParameterValue")
    private fun addParam(
        url: String,
        param: String,
        value: String,
    ) = Uri
        .parse(url)
        .buildUpon()
        .appendQueryParameter(param, value)
        .toString()

    override val client =
        network.client
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .addInterceptor { chain ->
                val newReq =
                    chain
                        .request()
                        .newBuilder()
                        .removeHeader("Cookie")
                        .addHeader("Cookie", cookiesHeader)
                        .build()

                chain.proceed(newReq)
            }.build()

    // Filters
    override fun getFilterList() = FilterList(
        EnforceLanguageFilter(getEnforceLanguagePref()),
        Watched(),
        GenreGroup(),
        Filter.Header("Tags — separate with , or ;  ·  prefix - to exclude"),
        FemaleTagFilter(namespaceSuggestions("female")),
        MaleTagFilter(namespaceSuggestions("male")),
        MixedTagFilter(namespaceSuggestions("mixed")),
        OtherTagFilter(otherSuggestions()),
        AdvancedGroup(),
    )

    // Most-used tags (by this user, on this source) first, then the full dictionary, de-duplicated.
    // This surfaces the tags the user searches most often at the top of the autocomplete dropdown.
    private fun orderedTagSuggestions(): List<String> = (mostUsedTags() + EH_TAG_SUGGESTIONS).distinct()

    // Suggestions for a single namespace's input box, with the "namespace:" prefix stripped
    // so the dropdown shows exactly what the user types into that box (e.g. "big breasts").
    private fun namespaceSuggestions(namespace: String): List<String> = orderedTagSuggestions()
        .filter { it.startsWith("$namespace:") }
        .map { it.removePrefix("$namespace:") }
        .distinct()

    // Suggestions for the general box: everything that isn't covered by a dedicated box,
    // kept fully-qualified (e.g. "parody:naruto") since that box is typed with prefixes.
    private fun otherSuggestions(): List<String> {
        val dedicated = listOf("female:", "male:", "mixed:")
        return orderedTagSuggestions()
            .filter { tag -> dedicated.none { tag.startsWith(it) } }
            .distinct()
    }

    // A tag input scoped to a single E-Hentai namespace. The user types bare tags
    // (e.g. "big breasts, -guro") and the namespace is added automatically.
    abstract class NamespaceTagFilter(
        name: String,
        private val namespace: String,
        suggestions: List<String>,
    ) : Filter.AutoComplete(
        name = name,
        hint = "e.g. big breasts, sole female; -guro",
        suggestions = suggestions,
    ) {
        // Turn the bare input into fully-qualified "namespace:tag" tokens, preserving "-" exclusions.
        fun qualifiedTags(): List<String> = state
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                val exclude = token.startsWith("-")
                val bare = token.removePrefix("-").trim()
                // If the user already typed a namespace, leave it; otherwise prepend this box's.
                val qualified = if (bare.contains(':')) bare else "$namespace:$bare"
                if (exclude) "-$qualified" else qualified
            }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    class FemaleTagFilter(suggestions: List<String>) : NamespaceTagFilter("Female tags", "female", suggestions)

    class MaleTagFilter(suggestions: List<String>) : NamespaceTagFilter("Male tags", "male", suggestions)

    class MixedTagFilter(suggestions: List<String>) : NamespaceTagFilter("Mixed tags", "mixed", suggestions)

    // General box for anything without a dedicated namespace box (parody:, artist:, language:, …).
    // Typed with full prefixes; the hint shows greyed-out examples.
    class OtherTagFilter(
        suggestions: List<String>,
    ) : Filter.AutoComplete(
        name = "Other tags",
        hint = "e.g. parody:naruto, artist:rei, language:english; -guro",
        suggestions = suggestions,
    ) {
        fun qualifiedTags(): List<String> = state
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    class Watched :
        CheckBox("Watched List"),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendPath("watched")
            }
        }
    }

    class GenreOption(
        name: String,
        private val genreId: String,
    ) : CheckBox(name, false),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            builder.appendQueryParameter("f_$genreId", if (state) "1" else "0")
        }
    }

    class GenreGroup :
        UriGroup<GenreOption>(
            "Genres",
            listOf(
                GenreOption("Dōjinshi", "doujinshi"),
                GenreOption("Manga", "manga"),
                GenreOption("Artist CG", "artistcg"),
                GenreOption("Game CG", "gamecg"),
                GenreOption("Western", "western"),
                GenreOption("Non-H", "non-h"),
                GenreOption("Image Set", "imageset"),
                GenreOption("Cosplay", "cosplay"),
                GenreOption("Asian Porn", "asianporn"),
                GenreOption("Misc", "misc"),
            ),
        )

    class AdvancedOption(
        name: String,
        private val param: String,
        defValue: Boolean = false,
    ) : CheckBox(name, defValue),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendQueryParameter(param, "on")
            }
        }
    }

    open class PageOption(
        name: String,
        private val queryKey: String,
    ) : Text(name),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state.isNotBlank()) {
                if (builder.build().getQueryParameters("f_sp").isEmpty()) {
                    builder.appendQueryParameter("f_sp", "on")
                }

                builder.appendQueryParameter(queryKey, state.trim())
            }
        }
    }

    class MinPagesOption : PageOption("Minimum Pages", "f_spf")

    class MaxPagesOption : PageOption("Maximum Pages", "f_spt")

    class RatingOption :
        Select<String>(
            "Minimum Rating",
            arrayOf(
                "Any",
                "2 stars",
                "3 stars",
                "4 stars",
                "5 stars",
            ),
        ),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state > 0) {
                builder.appendQueryParameter("f_srdd", (state + 1).toString())
                builder.appendQueryParameter("f_sr", "on")
            }
        }
    }

    // Explicit type arg for listOf() to workaround this: KT-16570
    class AdvancedGroup :
        UriGroup<Filter<*>>(
            "Advanced Options",
            listOf(
                AdvancedOption("Search Gallery Name", "f_sname", true),
                AdvancedOption("Search Gallery Tags", "f_stags", true),
                AdvancedOption("Search Gallery Description", "f_sdesc"),
                AdvancedOption("Search Torrent Filenames", "f_storr"),
                AdvancedOption("Only Show Galleries With Torrents", "f_sto"),
                AdvancedOption("Search Low-Power Tags", "f_sdt1"),
                AdvancedOption("Search Downvoted Tags", "f_sdt2"),
                AdvancedOption("Show Expunged Galleries", "f_sh"),
                RatingOption(),
                MinPagesOption(),
                MaxPagesOption(),
            ),
        )

    private class EnforceLanguageFilter(
        default: Boolean,
    ) : CheckBox("Enforce language", default)

    // map languages to their internal ids
    private val languageMappings =
        listOf(
            Pair("japanese", listOf("0", "1024", "2048")),
            Pair("english", listOf("1", "1025", "2049")),
            Pair("chinese", listOf("10", "1034", "2058")),
            Pair("dutch", listOf("20", "1044", "2068")),
            Pair("french", listOf("30", "1054", "2078")),
            Pair("german", listOf("40", "1064", "2088")),
            Pair("hungarian", listOf("50", "1074", "2098")),
            Pair("italian", listOf("60", "1084", "2108")),
            Pair("korean", listOf("70", "1094", "2118")),
            Pair("polish", listOf("80", "1104", "2128")),
            Pair("portuguese", listOf("90", "1114", "2138")),
            Pair("russian", listOf("100", "1124", "2148")),
            Pair("spanish", listOf("110", "1134", "2158")),
            Pair("thai", listOf("120", "1144", "2168")),
            Pair("vietnamese", listOf("130", "1154", "2178")),
            Pair("n/a", listOf("254", "1278", "2302")),
            Pair("other", listOf("255", "1279", "2303")),
        )

    companion object {
        const val QUERY_PREFIX = "?f_apply=Apply+Filter"
        const val PREFIX_ID_SEARCH = "id:"
        const val TR_SUFFIX = "TR"

        // Number of thumbnail pages to fetch concurrently when building the page list
        const val PAGE_FETCH_CONCURRENCY = 5

        // Preferences vals
        private const val ENFORCE_LANGUAGE_PREF_KEY = "ENFORCE_LANGUAGE"
        private const val ENFORCE_LANGUAGE_PREF_TITLE = "Enforce Language"
        private const val ENFORCE_LANGUAGE_PREF_SUMMARY = "If checked, forces browsing of manga matching a language tag"
        private const val ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE = false

        // Personalized tag-frequency tracking
        private const val TAG_USAGE_PREF_KEY = "TAG_USAGE"

        // Cap stored tags so the preference can't grow without bound; keep the most-used ones.
        private const val TAG_USAGE_MAX_ENTRIES = 300
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val enforceLanguagePref =
            CheckBoxPreference(screen.context).apply {
                key = "${ENFORCE_LANGUAGE_PREF_KEY}_$lang"
                title = ENFORCE_LANGUAGE_PREF_TITLE
                summary = ENFORCE_LANGUAGE_PREF_SUMMARY
                setDefaultValue(ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE)

                setOnPreferenceChangeListener { _, newValue ->
                    val checkValue = newValue as Boolean
                    preferences.edit().putBoolean("${ENFORCE_LANGUAGE_PREF_KEY}_$lang", checkValue).commit()
                }
            }
        screen.addPreference(enforceLanguagePref)
    }

    private fun getEnforceLanguagePref(): Boolean = preferences.getBoolean("${ENFORCE_LANGUAGE_PREF_KEY}_$lang", ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE)

    // region Personalized tag frequency
    // Usage is stored per source as newline-separated "tag\tcount" lines (tags never contain \t or \n).

    private fun usagePrefKey() = "${TAG_USAGE_PREF_KEY}_$lang"

    private fun getTagUsage(): Map<String, Int> = preferences
        .getString(usagePrefKey(), "")
        .orEmpty()
        .lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val sep = line.lastIndexOf('\t')
            if (sep <= 0) {
                return@mapNotNull null
            }
            val tag = line.substring(0, sep)
            val count = line.substring(sep + 1).toIntOrNull() ?: return@mapNotNull null
            tag to count
        }
        .toMap()

    private fun recordTagUsage(tags: List<String>) {
        // Normalize to the bare include form (drop a leading '-', lowercase) so an excluded tag
        // still counts toward familiarity and "female:X" / "-female:X" share a tally.
        val normalized = tags
            .map { it.removePrefix("-").trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (normalized.isEmpty()) {
            return
        }
        val usage = getTagUsage().toMutableMap()
        normalized.forEach { usage[it] = (usage[it] ?: 0) + 1 }
        val encoded = usage.entries
            .sortedByDescending { it.value }
            .take(TAG_USAGE_MAX_ENTRIES)
            .joinToString("\n") { "${it.key}\t${it.value}" }
        preferences.edit().putString(usagePrefKey(), encoded).apply()
    }

    private fun mostUsedTags(): List<String> = getTagUsage()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }
    // endregion
}
