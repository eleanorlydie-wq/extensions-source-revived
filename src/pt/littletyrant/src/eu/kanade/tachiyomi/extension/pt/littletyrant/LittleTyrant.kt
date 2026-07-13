package eu.kanade.tachiyomi.extension.pt.littletyrant

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class LittleTyrant :
    Madara(
        "Little Tyrant",
        "https://tiraninha.world",
        "pt-BR",
        dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale("pt", "BR")),
    ) {

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::imageDecodeInterceptor)
        .rateLimit(3, 1.seconds)
        .build()

    private val decoder by lazy { Decoder() }

    @Volatile
    private var readerToken: String? = null

    @Volatile
    private var readerKey: String? = null

    @Volatile
    private var readerAuthFetchedAt: Long = 0L

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    // =============================== Popular =================================

    override fun popularMangaSelector() = "[id*=manga-entry-]"
    override val popularMangaUrlSelector = ".card-title a"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        title = element.selectFirst("h3")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
        setUrlWithoutDomain(element.selectFirst(popularMangaUrlSelector)!!.absUrl("href"))
    }

    // =============================== Details =================================

    override val mangaDetailsSelectorGenre = ".mc-genres-pills a"
    override val mangaDetailsSelectorDescription = ".mc-description-box"
    override val mangaDetailsSelectorAuthor = ".mc-meta-grid .attr-item:has(.attr-label:contains(AUTOR)) .attr-value"
    override val mangaDetailsSelectorArtist = ".mc-meta-grid .attr-item:has(.attr-label:contains(ARTISTA)) .attr-value"
    override val mangaDetailsSelectorStatus = ".mc-meta-grid .attr-item:has(.attr-label:contains(STATUS)) .attr-value"

    override fun mangaDetailsParse(document: Document): SManga = super.mangaDetailsParse(document).apply {
        author = author?.replace(COMMA_REGEX, ", ")?.takeUnless { it.contains("---") }
        artist = artist?.replace(COMMA_REGEX, ", ")?.takeUnless { it.contains("---") }
    }

    // =============================== Chapters =================================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val document = client.newCall(mangaDetailsRequest(manga)).execute().asJsoup()
        val mangaId = document.selectFirst("a.wp-manga-action-button")!!.attr("data-post")
        val chapters = mutableListOf<SChapter>()
        val url = "$baseUrl/wp-admin/admin-ajax.php"
        var offset = 0
        do {
            val form = FormBody.Builder()
                .add("action", "load_more_chapters")
                .add("manga_id", mangaId)
                .add("offset", offset.toString())
                .build()
            offset += 12
            val dto = client.newCall(POST(url, headers, form)).execute().parseAs<ChapterDto>()
            val chapterElements = dto.toJsoup(baseUrl).select(chapterListSelector())
            chapters += chapterElements.map(::chapterFromElement)
        } while (!dto.isEmpty())

        chapters.sortedByDescending(SChapter::chapter_number)
    }

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = element.selectFirst(".chapter-name-label")!!.text()
        date_upload = parseChapterDate(element.selectFirst(".chapter-pub-date")?.text())
        // The source chapter list is out of order, so extract the number here for later sorting
        CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.last()?.toFloatOrNull()?.let {
            chapter_number = it
        }
        setUrlWithoutDomain(element.selectFirst("a.chapter-item-anchor")!!.absUrl("href"))
    }

    // =============================== Pages =================================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = client.newCall(pageListRequest(chapter))
        .asObservableSuccess()
        .map { response ->
            val doc = response.asJsoup()
            launchIO { countViews(doc) }

            decoder.extractPaths(doc, baseUrl).mapIndexed { idx, url ->
                Page(idx, imageUrl = url)
            }
        }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    // =============================== Images =================================

    override fun imageRequest(page: Page): Request {
        val imageHeaders = readerFetchHeaders()
            .newBuilder()
            .set("Accept", "image/webp,image/*,*/*")
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    // Every reading-page image is proxied through image-loader.php, which XORs the
    // first 1024 bytes of the response with a per-session key. The key is derived from
    // a token handed out by gatekeeper.php, which also requires the same "browser fetch"
    // looking headers (Sec-Fetch-*, Origin, X-Requested-With) or it 403s.
    private fun readerFetchHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("X-Reader-Sec", READER_SECRET)
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    private fun imageDecodeInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.contains("image-loader.php")) {
            return chain.proceed(request)
        }

        val (token, key) = readerAuth()
        var response = chain.proceed(request.withAuthCookie(token))

        if (!response.isSuccessful) {
            response.close()
            val (retryToken, retryKey) = readerAuth(forceRefresh = true)
            response = chain.proceed(request.withAuthCookie(retryToken))
            if (!response.isSuccessful) return response
            return response.xorDecoded(retryKey)
        }

        return response.xorDecoded(key)
    }

    private fun Request.withAuthCookie(token: String): Request = newBuilder()
        .header("Cookie", "lt_sec_val=$token")
        .build()

    private fun Response.xorDecoded(key: String): Response {
        val bytes = body.bytes()
        val limit = minOf(1024, bytes.size)
        for (i in 0 until limit) {
            bytes[i] = (bytes[i].toInt() xor key[i % key.length].code).toByte()
        }
        return newBuilder().body(bytes.toResponseBody(body.contentType())).build()
    }

    @Synchronized
    private fun readerAuth(forceRefresh: Boolean = false): Pair<String, String> {
        val now = System.currentTimeMillis()
        val cachedToken = readerToken
        val cachedKey = readerKey
        if (!forceRefresh && cachedToken != null && cachedKey != null && now - readerAuthFetchedAt < READER_AUTH_TTL_MS) {
            return cachedToken to cachedKey
        }

        val request = GET("$baseUrl/wp-content/themes/madara2/gatekeeper.php?t=$now", readerFetchHeaders())
        val dto = network.client.newCall(request).execute().parseAs<TokenDto>()
        val key = dto.token.substringAfter('.').substring(4, 20)

        readerToken = dto.token
        readerKey = key
        readerAuthFetchedAt = now
        return dto.token to key
    }

    companion object {
        private val CHAPTER_NUMBER_REGEX = """\d+(?:\.\d+)?""".toRegex()
        private val COMMA_REGEX = """,\s*""".toRegex()
        private const val READER_SECRET = "tiraninha-web"
        private const val READER_AUTH_TTL_MS = 60 * 60 * 1000L
    }
}
