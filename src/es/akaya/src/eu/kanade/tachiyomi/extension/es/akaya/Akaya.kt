package eu.kanade.tachiyomi.extension.es.akaya

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Akaya : HttpSource() {
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override val name = "AKAYA"

    override val baseUrl = "https://akaya.io"

    override val lang = "es"

    override val supportsLatest = true

    @Volatile
    private var csrfToken: String = ""

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (!request.url.toString().startsWith("$baseUrl/serie")) return@addInterceptor chain.proceed(request)
            val response = chain.proceed(request)
            if (response.request.url.toString().removeSuffix("/") == baseUrl) {
                response.close()
                throw IOException("Esta serie no se encuentra disponible")
            }
            response
        }
        .addInterceptor { chain ->
            val request = chain.request()
            if (!request.url.toString().startsWith("$baseUrl/search")) return@addInterceptor chain.proceed(request)
            val query = request.url.fragment ?: return@addInterceptor chain.proceed(request)
            if (csrfToken.isEmpty()) getCsrftoken()
            var response = chain.proceed(addFormBody(request, query))
            if (response.code == 419) {
                response.close()
                getCsrftoken()
                response = chain.proceed(addFormBody(request, query))
            }
            response
        }
        .rateLimit(1, 1.seconds) { it.host == baseUrlHost }
        .build()

    private fun getCsrftoken() {
        val response = client.newCall(GET(baseUrl, headers)).execute()
        csrfToken = response.asJsoup().selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
    }

    private fun addFormBody(request: Request, query: String): Request {
        val body = FormBody.Builder()
            .add("_token", csrfToken)
            .add("search", query)
            .build()

        return request.newBuilder()
            .url(request.url.toString().substringBefore("#"))
            .post(body)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/collection/bd90cb43-9bf2-4759-b8cc-c9e66a526bc6?page=$page", headers)

    override fun popularMangaParse(response: Response) = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/collection/0031a504-706c-4666-9782-a4ae30cad973?page=$page", headers)

    override fun latestUpdatesParse(response: Response) = parseMangaList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            return POST("$baseUrl/search#$query", headers)
        }

        val url = baseUrl.toHttpUrl().newBuilder()
        val order = filters.firstInstanceOrNull<OrderFilter>()?.toUriPart() ?: "genres"
        val genres = filters.firstInstanceOrNull<GenreFilter>()?.state
            ?.filter { it.state }
            ?.map { it.id }
            ?: emptyList()

        url.addPathSegment(order)
        if (genres.isNotEmpty()) {
            url.addPathSegment(genres.joinToString(",", "[", "]"))
        }

        url.addQueryParameter("page", page.toString())

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (!response.request.url.toString().contains("/search")) {
            return parseMangaList(response)
        }

        val document = response.asJsoup()
        val mangas = document.select("main > div.search-title > div.rowDiv div.list-search:has(div.inner-img-search)").map {
            SManga.create().apply {
                setUrlWithoutDomain(it.selectFirst("div.name-serie-search > a")!!.attr("href"))
                thumbnail_url = it.selectFirst("div.inner-img-search")?.attr("style")
                    ?.substringAfter("url(")?.substringBefore(")")
                title = it.select("div.name-serie-search")?.text() ?: ""
            }
        }

        return MangasPage(mangas, false)
    }

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div[role=link][onclick*=/serie/]").mapNotNull { element ->
            val path = element.attr("onclick").substringAfter("href='", "").substringBefore("'")
            if (path.isEmpty()) return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(path)
                title = element.selectFirst("h1")?.text()?.trim() ?: ""
                thumbnail_url = element.selectFirst("div[style*=background-image]")?.attr("style")
                    ?.substringAfter("url(")?.substringBefore(")")?.trim('\'', '"')
            }
        }
        // The site no longer exposes a pagination control; keep requesting while a page returns results.
        val hasNextPage = mangas.isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("Los filtros se ignorarán al hacer una búsqueda por texto"),
        Filter.Separator(),
        OrderFilter(),
        GenreFilter(),
    )

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            // The "Créditos" (author) span sits between the title span and the genre <ul>
            // inside the "Información" block, so it anchors all three without relying on
            // the framework's autogenerated utility-class names.
            val authorElement = document.selectFirst("span.text-secondary-800")
            title = authorElement?.previousElementSibling()?.text()?.trim() ?: title
            author = authorElement?.text()?.trim()
            genre = authorElement?.nextElementSibling()?.select("li")
                ?.mapNotNull { it.selectFirst("span")?.text()?.trim()?.ifEmpty { null } }
                ?.distinct()
                ?.joinToString()
            thumbnail_url = document.selectFirst("img.object-cover.rounded-xl")?.attr("abs:src")
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url + "?order_direction=desc", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("#chapters-container > div").mapNotNull { element ->
            val link = element.selectFirst("a[aria-label=Leer]") ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(link.attr("href"))
                val number = element.selectFirst("[data-flux-text]")?.text()
                    ?.filter { it.isDigit() || it == '.' }
                    ?: ""
                val episodeTitle = element.selectFirst("span.font-sans-bold.my-2")?.text()?.trim() ?: ""
                name = if (episodeTitle.isNotEmpty()) "Capítulo $number - $episodeTitle" else "Capítulo $number"
                chapter_number = number.toFloatOrNull() ?: -1f
                date_upload = dateFormat.tryParse(element.selectFirst("span.text-gray-300.text-sm.truncate")?.text())
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.url.substringAfterLast("#") == "lock") {
            throw Exception("Capítulo bloqueado")
        }
        return super.pageListRequest(chapter)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val scriptContent = document.selectFirst("script:containsData(var chapterData =)")?.data()

        if (scriptContent != null) {
            try {
                val jsonString = scriptContent
                    .substringAfter("var chapterData =")
                    .substringBefore("\n")
                    .trim()
                    .removeSuffix(";")

                val chapterData = jsonString.parseAs<ChapterDataDto>()
                if (chapterData.sortedImages.isNotEmpty()) {
                    return chapterData.sortedImages.mapIndexed { i, img ->
                        Page(i, imageUrl = "https://api.akayamedia.com/chapters/${img.image}")
                    }
                }
            } catch (e: Exception) {
                // Fallback to DOM parsing below
            }
        }

        // DOM order of img.chapter-img is not the reading order; the alt text ("Page N") is.
        return document.select("img.chapter-img").sortedBy { img ->
            img.attr("alt").filter { it.isDigit() }.toIntOrNull() ?: 0
        }.mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es"))
    }
}
