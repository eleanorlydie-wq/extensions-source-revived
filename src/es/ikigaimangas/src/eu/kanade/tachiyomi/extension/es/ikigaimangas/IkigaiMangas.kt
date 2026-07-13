package eu.kanade.tachiyomi.extension.es.ikigaimangas

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.applicationContext
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class IkigaiMangas :
    HttpSource(),
    ConfigurableSource {
    private val fetchedDomainUrlHost by lazy { fetchedDomainUrl.toHttpUrl().host }

    private val isCi = System.getenv("CI") == "true"

    override val baseUrl get() = when {
        isCi -> defaultBaseUrl
        else -> preferences.prefBaseUrl
    }

    private val defaultBaseUrl: String = "https://visorikigai.gettocaboca.com"

    private val fetchedDomainUrl: String by lazy {
        if (!preferences.fetchDomainPref()) return@lazy preferences.prefBaseUrl
        try {
            val initClient = network.client
            val headers = super.headersBuilder().build()
            val document = initClient.newCall(GET("https://ikigaimangas.com", headers)).execute().asJsoup()
            val scriptUrl = document.selectFirst("div[on:click]:containsOwn(Ir al sitio)")?.attr("on:click")
                ?: return@lazy preferences.prefBaseUrl
            val script = initClient.newCall(GET("https://ikigaimangas.com/build/$scriptUrl", headers)).execute().body.string()
            val domain = script.substringAfter("window.open(\"").substringBefore("\"")
            val host = initClient.newCall(GET(domain, headers)).execute().request.url.host
            val newDomain = "https://$host"
            preferences.prefBaseUrl = newDomain
            newDomain
        } catch (e: Exception) {
            preferences.prefBaseUrl
        }
    }

    private val imageCdnUrl: String = "https://image.ikigaimangas.cloud"

    override val lang: String = "es"

    override val name: String = "Ikigai Mangas"

    override val supportsLatest: Boolean = true

    override val client by lazy {
        network.client.newBuilder()
            .addNetworkInterceptor(::nsfwCookieInterceptor)
            .rateLimit(2, 1.seconds) { it.host == fetchedDomainUrlHost }
            .build()
    }

    private fun nsfwCookieInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return request.header("X-Add-Nsfw-Cookie")?.let {
            val newRequest = request.newBuilder()
                .removeHeader("X-Add-Nsfw-Cookie")
                .setCookie("nsfw-mode", "true")
                .build()
            chain.proceed(newRequest)
        } ?: chain.proceed(request)
    }

    private fun Request.Builder.setCookie(name: String, value: String): Request.Builder {
        val existingHeader = this.build().header("Cookie") ?: ""

        val cookies = existingHeader
            .split(";")
            .mapNotNull {
                val parts = it.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap().toMutableMap()

        cookies[name] = value

        val mergedHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        return this.header("Cookie", mergedHeader)
    }

    private val preferences: SharedPreferences = getPreferences()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$fetchedDomainUrl/")

    private val json: Json by injectLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val chapterDateFormat = SimpleDateFormat("EEE MMM d yyyy HH:mm:ss 'GMT'Z", Locale.US)

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/clasificacion/".toHttpUrl().newBuilder()
            .addQueryParameter("periodo", "total_ranking")
            .addQueryParameter("tipo", "comic")
            .build()

        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.card.bg-base-200").mapNotNull { card ->
            val title = card.selectFirst("h2.card-title")?.text() ?: return@mapNotNull null
            val href = card.selectFirst("a[href*=/series/]")?.attr("abs:href") ?: return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(href)
                thumbnail_url = card.selectFirst("img")?.attr("abs:src")
            }
        }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("new_chapters", "all")
            .addQueryParameter("pagina", page.toString())
            .build()

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val section = document.selectFirst("section[aria-labelledby=new-chapters-heading]")

        val mangas = section?.select("a[href*=/series/]")?.mapNotNull { a ->
            val title = a.selectFirst("h2.card-title")?.text() ?: return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(a.attr("abs:href"))
                thumbnail_url = a.selectFirst("img")?.attr("abs:src")
            }
        }.orEmpty()

        val currentPage = response.request.url.queryParameter("pagina")?.toIntOrNull() ?: 1
        val maxPage = section?.select("nav[aria-label=pagination] a")
            ?.mapNotNull { PAGINA_REGEX.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            ?.maxOrNull() ?: currentPage

        return MangasPage(mangas, currentPage < maxPage)
    }

    private var seriesCache: List<QwikSeriesDto>? = null

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        if (query.isNotEmpty()) {
            if (seriesCache != null) {
                return Observable.just(qwikDataParse(query, seriesCache!!, page))
            }
            val series = getQuerySeriesList()
            return Observable.just(qwikDataParse(query, series, page))
        }

        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response -> searchMangaParse(response) }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series/".toHttpUrl().newBuilder()
            .addQueryParameter("pagina", page.toString())

        val genres = filters.firstInstanceOrNull<GenreFilter>()?.state.orEmpty()
            .filter(Genre::state)
            .map(Genre::id)

        val statuses = filters.firstInstanceOrNull<StatusFilter>()?.state.orEmpty()
            .filter(Status::state)
            .map(Status::id)

        genres.forEach { url.addQueryParameter("generos[]", it.toString()) }
        statuses.forEach { url.addQueryParameter("estados[]", it.toString()) }

        return GET(url.build(), headers)
    }

    private fun getQuerySeriesList(): List<QwikSeriesDto> {
        val baseUrl = preferences.prefBaseUrl
        val qfunc = getQfuncFromWebView(baseUrl, headers) ?: throw Exception("Ocurrio un error al obtener la lista de series")
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("qfunc", qfunc)
            .build()
        val payload = """{"_entry":"1","_objs":["\u0002_#s_$qfunc",["0"]]}"""
        val body = payload.toRequestBody()
        val headers = headersBuilder()
            .set("X-QRL", qfunc)
            .set("Content-Type", "application/qwik-json")
            .build()
        val response = client.newCall(POST(url.toString(), headers, body)).execute()
        return response.parseAs<QwikData>().parseAsList<QwikSeriesDto>().also { seriesCache = it }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a.card[href*=/series/]").mapNotNull { a ->
            val title = a.selectFirst("h3")?.text() ?: return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(a.attr("abs:href"))
                thumbnail_url = a.selectFirst("img")?.attr("abs:src")
            }
        }

        val currentPage = response.request.url.queryParameter("pagina")?.toIntOrNull() ?: 1
        val maxPage = document.select("nav[aria-label=pagination] a")
            .mapNotNull { PAGINA_REGEX.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: currentPage

        return MangasPage(mangas, currentPage < maxPage)
    }

    private fun qwikDataParse(query: String, seriesList: List<QwikSeriesDto>, page: Int): MangasPage {
        val nsfwEnabled = preferences.showNsfwPref

        val filteredSeries = seriesList
            .filter { it.type == "comic" }
            .filter { nsfwEnabled || !it.isMature }
            .filter { it.name.contains(query, ignoreCase = true) }

        val pagedSeries = filteredSeries
            .drop((page - 1) * PAGE_SIZE)
            .take(PAGE_SIZE)
            .map { it.toSManga(imageCdnUrl) }

        return MangasPage(pagedSeries, filteredSeries.size > page * PAGE_SIZE)
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val article = document.selectFirst("article.card")

        val statusHref = article?.select("a[href]")?.map { it.attr("href") }?.firstOrNull { it.contains("estados") }
        val statusId = statusHref?.let { ESTADOS_REGEX.find(it)?.groupValues?.get(1)?.toLongOrNull() }

        return SManga.create().apply {
            title = document.selectFirst("h1.card-title")?.text().orEmpty()
            thumbnail_url = article?.selectFirst("img")?.attr("abs:src")
            description = document.selectFirst("p.line-clamp-3")?.text()
            genre = document.select("li.badge-accent > a").joinToString { it.text() }
            status = parseSeriesStatus(statusId)
        }
    }

    private fun parseSeriesStatus(statusId: Long?) = when (statusId) {
        906397890812182531, 911437469204086787 -> SManga.ONGOING
        906409397258190851 -> SManga.ON_HIATUS
        906409532796731395, 911793517664960513 -> SManga.COMPLETED
        906426661911756802, 906428048651190273, 911793767845265410, 911793856861798402 -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}?pagina=1", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        chapters += parseChapterListDocument(document)

        val basePath = response.request.url.toString().substringBefore("?pagina=").substringBefore("?")
        val maxPage = document.select("nav[aria-label=pagination] a")
            .mapNotNull { PAGINA_REGEX.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1

        for (page in 2..maxPage) {
            val newDocument = client.newCall(GET("$basePath?pagina=$page", headers)).execute().asJsoup()
            chapters += parseChapterListDocument(newDocument)
        }

        return chapters
    }

    private fun parseChapterListDocument(document: Document): List<SChapter> = document.select("a[href^=/capitulo/]").mapNotNull { a ->
        val nameEl = a.selectFirst("h3.card-title") ?: return@mapNotNull null
        SChapter.create().apply {
            setUrlWithoutDomain(a.attr("abs:href"))
            name = nameEl.text()
            date_upload = a.selectFirst("time[datetime]")?.attr("datetime")?.let(::parseChapterDate) ?: 0L
        }
    }

    private fun parseChapterDate(raw: String): Long = try {
        chapterDateFormat.parse(raw.substringBefore(" ("))?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img[alt^=Página]")
            .map { it.attr("abs:src") }
            .filterNot { it.contains("/posts/") || it.endsWith("zzzz.webp") }
            .distinct()
            .mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList {
        fetchFilters()

        val filters = mutableListOf<Filter<*>>(
            Filter.Header("Nota: Los filtros son ignorados si se realiza una búsqueda por texto."),
            Filter.Separator(),
            SortByFilter("Ordenar por", getSortProperties()),
        )

        filters += if (filtersState == FiltersState.FETCHED) {
            listOf(
                StatusFilter("Estados", getStatusFilters()),
                GenreFilter("Géneros", getGenreFilters()),
            )
        } else {
            listOf(
                Filter.Header("Presione 'Restablecer' para intentar cargar los filtros"),
            )
        }

        return FilterList(filters)
    }

    private fun getSortProperties(): List<SortProperty> = listOf(
        SortProperty("Nombre", "name"),
        SortProperty("Creado en", "created_at"),
        SortProperty("Actualización más reciente", "last_chapter_date"),
        SortProperty("Número de favoritos", "bookmark_count"),
        SortProperty("Número de valoración", "rating_count"),
        SortProperty("Número de vistas", "view_count"),
    )

    private fun getGenreFilters(): List<Genre> = genresList.map { Genre(it.first, it.second) }
    private fun getStatusFilters(): List<Status> = statusesList.map { Status(it.first, it.second) }

    private var genresList: List<Pair<String, Long>> = emptyList()
    private var statusesList: List<Pair<String, Long>> = emptyList()
    private var fetchFiltersAttempts = 0
    private var filtersState = FiltersState.NOT_FETCHED

    private fun fetchFilters() {
        if (filtersState != FiltersState.NOT_FETCHED || fetchFiltersAttempts >= 3) return
        filtersState = FiltersState.FETCHING
        fetchFiltersAttempts++
        thread {
            try {
                val document = client.newCall(GET(baseUrl, headers)).execute().asJsoup()
                val links = document.select("a[href*=/series/]")

                val genres = links.mapNotNull { a ->
                    val id = GENEROS_REGEX.find(a.attr("href"))?.groupValues?.get(1)?.toLongOrNull()
                        ?: return@mapNotNull null
                    a.text().trim() to id
                }.distinctBy { it.second }

                val statuses = links.mapNotNull { a ->
                    val id = ESTADOS_REGEX.find(a.attr("href"))?.groupValues?.get(1)?.toLongOrNull()
                        ?: return@mapNotNull null
                    a.text().trim() to id
                }.distinctBy { it.second }

                if (genres.isNotEmpty()) {
                    genresList = genres
                    statusesList = statuses
                    filtersState = FiltersState.FETCHED
                } else {
                    filtersState = FiltersState.NOT_FETCHED
                }
            } catch (e: Throwable) {
                filtersState = FiltersState.NOT_FETCHED
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_NSFW_PREF
            title = SHOW_NSFW_PREF_TITLE
            setDefaultValue(SHOW_NSFW_PREF_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                cachedNsfwPref = newValue as Boolean
                true
            }
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = FETCH_DOMAIN_PREF
            title = FETCH_DOMAIN_PREF_TITLE
            summary = FETCH_DOMAIN_PREF_SUMMARY
            setDefaultValue(FETCH_DOMAIN_PREF_DEFAULT)
        }.also { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "URL por defecto:\n$defaultBaseUrl"
            setDefaultValue(defaultBaseUrl)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP_MESSAGE, Toast.LENGTH_LONG).show()
                true
            }
        }.also { screen.addPreference(it) }
    }

    private fun SharedPreferences.fetchDomainPref() = getBoolean(FETCH_DOMAIN_PREF, FETCH_DOMAIN_PREF_DEFAULT)

    private var cachedBaseUrl: String? = null
    private var SharedPreferences.prefBaseUrl: String
        get() {
            if (cachedBaseUrl == null) {
                cachedBaseUrl = getString(BASE_URL_PREF, defaultBaseUrl)!!
            }
            return cachedBaseUrl!!
        }
        set(value) {
            cachedBaseUrl = value
            edit().putString(BASE_URL_PREF, value).apply()
        }

    private var cachedNsfwPref: Boolean? = null
    private var SharedPreferences.showNsfwPref: Boolean
        get() {
            if (cachedNsfwPref == null) {
                cachedNsfwPref = getBoolean(SHOW_NSFW_PREF, SHOW_NSFW_PREF_DEFAULT)
            }
            return cachedNsfwPref!!
        }
        set(value) {
            cachedNsfwPref = value
            edit().putBoolean(SHOW_NSFW_PREF, value).apply()
        }

    private inline fun <reified R> List<*>.firstInstanceOrNull(): R? = filterIsInstance<R>().firstOrNull()

    private enum class FiltersState { NOT_FETCHED, FETCHING, FETCHED }

    private fun getQfuncFromWebView(url: String, headers: Headers): String? {
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        val pool = ('a'..'z') + ('A'..'Z')
        val interfaceName = (1..(10..20).random())
            .map { pool.random() }
            .joinToString("")
        var result: String? = null
        var webView: WebView? = null
        handler.post {
            webView = WebView(applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.blockNetworkImage = true
                settings.userAgentString = headers["User-Agent"]
                addJavascriptInterface(
                    object {
                        @Suppress("unused")
                        @JavascriptInterface
                        fun onQfunc(value: String) {
                            result = value
                            latch.countDown()
                        }
                    },
                    interfaceName,
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        super.onPageFinished(view, loadedUrl)
                        injectFetchInterceptor(view, interfaceName)
                        clickTargetButton(view)
                    }
                }
                loadUrl(url)
            }
        }
        latch.await(20, TimeUnit.SECONDS)
        handler.post {
            webView?.destroy()
        }
        return result
    }

    private fun injectFetchInterceptor(
        webView: WebView,
        interfaceName: String,
    ) {
        val script = """
        (function () {
            const originalFetch = window.fetch;
            window.fetch = async function(resource, options) {
                let url = "";
                if (typeof resource === "string") {
                    url = resource;
                } else if (resource && resource.url) {
                    url = resource.url;
                }
                if (url.includes("qfunc")) {
                    const match = url.match(/[?&]qfunc=([^&]+)/);
                    if (match) {
                        const qfunc = decodeURIComponent(match[1]);
                        window.$interfaceName.onQfunc(qfunc);
                    }
                }
                return originalFetch.apply(this, arguments);
            };
        })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun clickTargetButton(webView: WebView) {
        val script = """
        (function () {
            let tries = 0;
            const interval = setInterval(() => {
                const btn = [...document.querySelectorAll('button')]
                    .find(button =>
                        [...button.querySelectorAll('span')]
                            .some(span =>
                                span.textContent?.trim().includes('Buscar...')
                            )
                    );
                if (btn) {
                    clearInterval(interval);
                    btn.click();
                    return;
                }
                tries++;
                if (tries >= 20) {
                    clearInterval(interval);
                }
            }, 500);
        })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    companion object {
        private const val SHOW_NSFW_PREF = "pref_show_nsfw"
        private const val SHOW_NSFW_PREF_TITLE = "Mostrar contenido NSFW"
        private const val SHOW_NSFW_PREF_DEFAULT = false

        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Editar URL de la fuente"
        private const val BASE_URL_PREF_SUMMARY = "Para uso temporal, si la extensión se actualiza se perderá el cambio."
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val RESTART_APP_MESSAGE = "Reinicie la aplicación para aplicar los cambios"

        private const val FETCH_DOMAIN_PREF = "fetchDomain"
        private const val FETCH_DOMAIN_PREF_TITLE = "Buscar dominio automáticamente"
        private const val FETCH_DOMAIN_PREF_SUMMARY = "Intenta buscar el dominio automáticamente al abrir la fuente."
        private const val FETCH_DOMAIN_PREF_DEFAULT = true

        private const val PAGE_SIZE = 20

        private val PAGINA_REGEX = Regex("""pagina=(\d+)""")
        private val ESTADOS_REGEX = Regex("""estados(?:%5B%5D|\[])=(\d+)""")
        private val GENEROS_REGEX = Regex("""generos(?:%5B%5D|\[])=(\d+)""")
    }

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { domain ->
            if (domain != defaultBaseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }
}
