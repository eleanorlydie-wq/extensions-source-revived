package eu.kanade.tachiyomi.extension.all.hentaitorrentimages

import android.app.Application
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Image sets (artwork, CG and manga) from hentaitorrents.com, read straight from their torrents
 * through the app's built-in torrent server.
 */
class HentaiTorrentImages : HttpSource() {

    override val name = "Hentai Torrent (Images)"

    override val baseUrl = "https://www.hentaitorrents.com"

    override val lang = "all"

    override val supportsLatest = false

    // Images come from the local torrent server, which may need to fetch pieces from peers first.
    override val client: OkHttpClient = network.client.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .addInterceptor(::torrentServerInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // A stalled download must not hold a listing up for the reader's long timeout.
    private val checkClient: OkHttpClient = network.client.newBuilder()
        .callTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // The server stops itself when idle; keep it up while pages are being read.
    private fun torrentServerInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host == LOCALHOST) TorrentServer.keepAlive()
        return chain.proceed(request)
    }

    // ============================== Listing ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page", headers)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable { fetchListing(page, ::popularMangaRequest) }

    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable.fromCallable {
        // A shared post link opens that post.
        if (query.startsWith("https://")) {
            if (query.toHttpUrl().host != baseUrl.toHttpUrl().host) throw Exception("Unsupported url")
            val response = client.newCall(GET(query, headers)).execute()
            val manga = response.use { mangaDetailsParse(it.asJsoup()) }.apply {
                setUrlWithoutDomain(response.request.url.toString())
            }
            return@fromCallable MangasPage(listOf(manga), false)
        }
        fetchListing(page) { searchMangaRequest(it, query, filters) }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.let { CATEGORIES[it.state].second }
        return when {
            query.isNotBlank() -> GET("$baseUrl/s.php?search=${URLEncoder.encode(query.trim(), "UTF-8")}&page=$page", headers)
            category.isNullOrEmpty() -> popularMangaRequest(page)
            else -> GET("$baseUrl/catalog/$category/page/$page", headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(CategoryFilter())

    private class CategoryFilter : Filter.Select<String>("Category", CATEGORIES.map { it.first }.toTypedArray())

    // An empty page reads as "no results" in the browse screen, so skip ahead (bounded) past
    // pages whose entries were all filtered out.
    private fun fetchListing(page: Int, request: (Int) -> Request): MangasPage {
        var current = page
        while (true) {
            val document = client.newCall(request(current)).execute().use {
                if (!it.isSuccessful) throw IOException("HTTP error ${it.code}")
                it.asJsoup()
            }
            val result = parseListing(document)
            if (result.mangas.isNotEmpty() || !result.hasNextPage || current - page >= MAX_PAGE_SKIP) {
                return result
            }
            current++
        }
    }

    // The tracker mixes image sets with videos, games and zipped collections, and its labels are
    // unreliable, so every entry is checked against its .torrent's file list: only posts that are
    // made of viewable images are listed.
    private fun parseListing(document: Document): MangasPage {
        val cards = document.select("div.image-container div.image-wrapper")
        val checks = cards
            .filterNot { card -> card.selectFirst("div.catalog")?.text().orEmpty().let { cat -> SKIPPED_CATALOGS.any { cat.contains(it, true) } } }
            .filterNot { card -> BLOCKED_CONTENT.containsMatchIn(card.text()) }
            .map { card -> card to checker.submit<Boolean> { isImagePost(card) } }
        val mangas = checks.filter { it.second.get() }.map { (card, _) ->
            SManga.create().apply {
                val link = card.selectFirst("a.overlay")!!
                setUrlWithoutDomain(link.attr("abs:href"))
                title = cleanTitle(link.text())
                thumbnail_url = card.selectFirst("img")?.attr("abs:src")
            }
        }
        saveVerdicts()
        // Catalog pages render an empty pagination block, so a full page is the only "more" signal.
        val hasNextPage = document.selectFirst("div.pagination a:contains(Next)") != null || cards.size >= PAGE_SIZE
        return MangasPage(mangas, hasNextPage)
    }

    private val checker = Executors.newFixedThreadPool(CHECK_THREADS) { Thread(it, "HentaiTorrentImages").apply { isDaemon = true } }

    // Verdicts per post (slug), kept across sessions: a post's torrent doesn't change.
    private val verdicts = ConcurrentHashMap<String, Boolean>()
    private val newVerdicts = ConcurrentHashMap<String, Boolean>()
    private val verdictPrefs by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_verdicts", 0x0000)
    }

    private val torrentUrls = ConcurrentHashMap<String, String>()

    private fun isImagePost(card: Element): Boolean {
        val postUrl = card.selectFirst("a.overlay")?.attr("abs:href").orEmpty()
        if (postUrl.isEmpty()) return false
        val key = postUrl.substringAfterLast('/')
        verdicts[key]?.let { return it }
        if (verdictPrefs.contains(key)) {
            return verdictPrefs.getBoolean(key, false).also { verdicts[key] = it }
        }
        // Network trouble: hide the post for now and check again next time.
        val verdict = try {
            checkTorrent(card, postUrl)
        } catch (_: Exception) {
            return false
        }
        verdicts[key] = verdict
        newVerdicts[key] = verdict
        return verdict
    }

    private fun saveVerdicts() {
        if (newVerdicts.isEmpty()) return
        val pending = HashMap(newVerdicts)
        newVerdicts.keys.removeAll(pending.keys)
        val editor = verdictPrefs.edit()
        if (verdictPrefs.all.size + pending.size > MAX_SAVED_VERDICTS) editor.clear()
        pending.forEach { (key, value) -> editor.putBoolean(key, value) }
        editor.apply()
    }

    /** Whether the post's torrent is an image set. Dead, missing and archived ones count as not. */
    private fun checkTorrent(card: Element, postUrl: String): Boolean {
        // Thumbnails are named after the torrent id (".../mini/202610/3313954_a1-500.jpg"),
        // which saves the two page loads behind the download button.
        val torrentId = THUMBNAIL_ID.find(card.selectFirst("img")?.attr("src").orEmpty())?.groupValues?.get(1)
        var torrentUrl = torrentId?.let(::torrentUrl)
        var bytes = torrentUrl?.let(::downloadTorrent)
        if (bytes == null) {
            torrentUrl = resolveTorrentUrl(postUrl) ?: return false
            bytes = downloadTorrent(torrentUrl) ?: return false
        }
        torrentUrls[postUrl.toHttpUrl().encodedPath] = torrentUrl!!
        return isImageSet(TorrentContents.parse(bytes))
    }

    private fun isImageSet(files: List<TorrentContents.Entry>): Boolean {
        val total = files.sumOf { it.size }
        val images = files.filter { it.extension in IMAGE_EXTENSIONS }.sumOf { it.size }
        val unusable = files.filter { it.extension in UNUSABLE_EXTENSIONS }.sumOf { it.size }
        return total > 0 && images * 5 >= total * 4 && unusable * 100 < total
    }

    private fun torrentUrl(torrentId: String) = "https://h.hentaitorrents.com/bt/%5Bhentaitorrents.com%5D.$torrentId.torrent"

    /** Detail page -> download page -> .torrent link; null when the post has none. */
    private fun resolveTorrentUrl(postUrl: String): String? {
        val downloadPageUrl = checkClient.newCall(GET(postUrl, headers)).execute().use { it.asJsoup() }
            .selectFirst("div.download-container a.download-button")?.attr("abs:href")
            .takeUnless { it.isNullOrEmpty() } ?: return null
        return checkClient.newCall(GET(downloadPageUrl, headers)).execute().use { it.asJsoup() }
            .selectFirst("a.download-button")?.attr("abs:href")
            .takeUnless { it.isNullOrEmpty() }
    }

    /** The .torrent's bytes, or null when it's gone (404 or not a torrent). */
    private fun downloadTorrent(url: String): ByteArray? = checkClient.newCall(GET(url, headers)).execute().use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val source = response.body.source()
        if (source.request(MAX_TORRENT_SIZE + 1)) throw IOException("Torrent file too large")
        source.buffer.readByteArray().takeIf { it.isNotEmpty() && it[0] == 'd'.code.toByte() }
    }

    private fun torrentUrlFor(postPath: String): String = torrentUrls[postPath]
        ?: resolveTorrentUrl(baseUrl + postPath)?.also { torrentUrls[postPath] = it }
        ?: throw Exception("This post has no torrent")

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        // Set only when found so the listing's title/thumbnail are never blanked.
        document.selectFirst("h1")?.text()?.let { title = cleanTitle(it) }
        document.selectFirst("div.container img[src*=/pic/]")?.attr("abs:src")?.let { thumbnail_url = it }
        val lines = document.selectFirst("div.article-content")?.let { content ->
            content.select("br").before("\\n")
            content.text().split("\\n").map { it.trim() }.filter { it.isNotEmpty() }
        }.orEmpty()
        genre = lines.firstNotNullOfOrNull { GENRE_LINE.find(it)?.groupValues?.get(1) }
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString()
        // The first line repeats the raw post title; the title loses the size, so keep it here.
        val size = TITLE_SIZE.find(document.selectFirst("h1")?.text().orEmpty())?.let { "Size: ${it.groupValues[1]}" }
        val body = lines.drop(if (lines.firstOrNull()?.startsWith("[") == true) 1 else 0).filterNot { GENRE_LINE.containsMatchIn(it) }
        description = (listOfNotNull(size) + body).joinToString("\n")
        status = SManga.COMPLETED
        initialized = true
    }

    // "[651.2 MB] [ART, Comix] Name [2011-2026, Full Color, ...] [JPG PNG] [eng, rus]" -> "Name".
    private fun cleanTitle(raw: String): String {
        var title = raw.trim().replace(TITLE_PREFIX, "")
        while (true) {
            val stripped = title.replace(TITLE_TAGS_SUFFIX, "").trim()
            if (stripped == title || stripped.isEmpty()) break
            title = stripped
        }
        return title.ifEmpty { raw.trim() }
    }

    // ============================== Chapters ==============================

    // Each folder of images is a chapter (large folders are split), read from the torrent itself.
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        imageGroups(manga.url, manga.title).mapIndexed { index, group ->
            SChapter.create().apply {
                url = "${manga.url}#${group.key}"
                name = group.name
                chapter_number = index + 1F
                scanlator = "${group.files.size} images"
            }
        }.reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.substringBefore('#')

    // =============================== Pages ================================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val postPath = chapter.url.substringBefore('#')
        val key = chapter.url.substringAfter('#', "")
        val group = imageGroups(postPath, chapter.name).firstOrNull { it.key == key }
            ?: throw Exception("These images are no longer in the torrent")
        group.files.mapIndexed { index, file -> Page(index, imageUrl = TorrentServer.streamUrl(file)) }
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private class ImageGroup(val key: String, val name: String, val files: List<TorrentFile>)

    // Chapter lists and each chapter's pages all need the file list; ask the server only once.
    private val groupsCache = object : LinkedHashMap<String, List<ImageGroup>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ImageGroup>>) = size > MAX_CACHED_TORRENTS
    }

    private fun imageGroups(postPath: String, title: String): List<ImageGroup> {
        synchronized(groupsCache) { groupsCache[postPath] }?.let { return it }
        return loadImageGroups(postPath, title).also { groups -> synchronized(groupsCache) { groupsCache[postPath] = groups } }
    }

    private fun loadImageGroups(postPath: String, title: String): List<ImageGroup> {
        // Hard-coded and not configurable. Covers titles and tags only, so untagged posts can get through.
        val postText = client.newCall(GET(baseUrl + postPath, headers)).execute().use { it.asJsoup() }
            .select("h1, div.catalog, div.article-content").text()
        if (BLOCKED_CONTENT.containsMatchIn("$title $postText")) throw Exception("This title is blocked")
        val info = TorrentServer.info(torrentUrlFor(postPath), title)
        val images = info.files.filter { it.path.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS }
        if (images.isEmpty()) throw Exception("This torrent has no images")
        // Paths start with the torrent's own folder when it has several files; that's not a chapter.
        val root = images.first().path.substringBefore('/', "")
            .takeIf { it.isNotEmpty() && images.all { file -> file.path.startsWith("$it/") } }
        val folders = images.groupBy { file ->
            val path = if (root != null) file.path.removePrefix("$root/") else file.path
            path.substringBeforeLast('/', "")
        }
        // Folders every chapter has in common only make the names longer.
        val shared = sharedFolderPrefix(folders.keys)
        return folders.keys.sortedWith(naturalOrder).flatMap { folder ->
            val files = folders.getValue(folder).sortedWith(compareBy(naturalOrder) { it.path })
            val label = folder.removePrefix(shared).ifEmpty { "Gallery" }.replace("/", " / ")
            if (files.size <= MAX_CHAPTER_IMAGES) {
                listOf(ImageGroup(folder, label, files))
            } else {
                files.chunked(MAX_CHAPTER_IMAGES).mapIndexed { part, chunk ->
                    ImageGroup("$folder@${part + 1}", "$label (part ${part + 1})", chunk)
                }
            }
        }
    }

    private fun sharedFolderPrefix(folders: Set<String>): String {
        // A lone folder adds nothing to a lone chapter's name.
        if (folders.size < 2) return folders.firstOrNull().orEmpty()
        val split = folders.map { it.split('/') }
        val depth = (0 until split.minOf { it.size }).takeWhile { i -> split.all { it[i] == split[0][i] } }.size
        // Never swallow a whole folder name: a chapter must keep something to be told apart by.
        val usable = minOf(depth, split.minOf { it.size } - 1)
        return if (usable <= 0) "" else split[0].take(usable).joinToString("/") + "/"
    }

    // "page2" before "page10".
    private val naturalOrder = Comparator<String> { a, b ->
        val x = NUMBER_OR_TEXT.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val y = NUMBER_OR_TEXT.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val cmp = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                    ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                p.compareTo(q)
            }
            if (cmp != 0) return@Comparator cmp
        }
        x.size.compareTo(y.size)
    }

    companion object {
        private val BLOCKED_CONTENT = Regex("""\b(loli|lolis|lolicon|lolita|shota|shotas|shotacon|toddlercon|underage|shoujo kyouiku)\b""", RegexOption.IGNORE_CASE)

        private const val LOCALHOST = "127.0.0.1"

        private const val PAGE_SIZE = 60
        private const val MAX_PAGE_SKIP = 5
        private const val CHECK_THREADS = 6
        private const val CHECK_TIMEOUT_SECONDS = 30L
        private const val MAX_CACHED_TORRENTS = 8

        private const val MAX_SAVED_VERDICTS = 4000
        private const val MAX_TORRENT_SIZE = 16L * 1024 * 1024
        private const val MAX_CHAPTER_IMAGES = 200

        private val THUMBNAIL_ID = Regex("""/mini/\d+/(\d+)[a-z]?(?:_a\d+)?-\d+\.\w+$""")
        private val NUMBER_OR_TEXT = Regex("""\d+|\D+""")

        private val GENRE_LINE = Regex("""^Genre:\s*(.+)$""")

        private val TITLE_SIZE = Regex("""^\[([\d.,]+\s*[KMGT]?B)]""")

        // Leading "[1.2 GB]" and "[ART, Comix]"-style format tags.
        private val TITLE_PREFIX = Regex("""^(?:\[[\d.,]+\s*[KMGT]?B]\s*)?(?:\[(?:[^\]]*[ ,])?(?:ART|Comix|CG|Manga)(?:[ ,][^\]]*)?]\s*)?""")

        // Trailing genre lists, file formats and languages. Short tags like "[Final]" are kept
        // unless they are a known format or a language code.
        private val TITLE_TAGS_SUFFIX = Regex("""\[(?:[^\]]*,[^\]]*|(?:JPE?G|PNG|GIF|WEBP)(?:[ ,]+(?:JPE?G|PNG|GIF|WEBP))*|(?:eng|rus|jap|kor|chi|multi)(?:[ +/,]+(?:eng|rus|jap|kor|chi|multi))*|(?:pt|un)?cen)]\s*$""", RegexOption.IGNORE_CASE)

        // Never image sets, and checking their (large) torrents would only slow the listing down.
        private val SKIPPED_CATALOGS = listOf("Game", "Software", "Cartoons", "video", "DVD", "Main Subsection")

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "jfif", "png", "gif", "webp", "avif", "bmp")

        // Archives, disk images and program files: nothing the reader can show.
        private val UNUSABLE_EXTENSIONS = setOf(
            "zip", "rar", "7z", "cbz", "cbr", "cb7", "tar", "gz", "tgz", "bz2", "xz", "lzh",
            "iso", "mdf", "exe", "dll", "apk", "msi", "swf", "jar", "bin", "pak", "pck", "rpa", "xp3",
            "rpgmvp", "rpgmvo", "ress", "assets", "resource", "bundle", "asar",
        )

        private val CATEGORIES = listOf(
            "All" to "",
            "Comics Artwork" to "Comics%20Artwork",
            "Artwork HCG Hentai" to "Artwork%20HCG%20Hentai",
            "Manga Hentai" to "Manga%20Hentai",
        )
    }
}
