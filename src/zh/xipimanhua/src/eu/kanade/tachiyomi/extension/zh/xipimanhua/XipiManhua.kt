package eu.kanade.tachiyomi.extension.zh.xipimanhua

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.math.BigInteger

class XipiManhua : HttpSource() {

    override val name = "Xipi Manhua"

    override val baseUrl = "https://m.hipmh.com"

    override val lang = "zh"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(4) { it.host == API_HOST }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int) = mangaListRequest(page, sort = "popular")

    override fun popularMangaParse(response: Response) = parseMangaList(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int) = mangaListRequest(page, sort = "updated")

    override fun latestUpdatesParse(response: Response) = parseMangaList(response)

    private fun mangaListRequest(
        page: Int,
        sort: String,
        category: String? = null,
        genre: String? = null,
        status: String? = null,
    ): Request {
        val url = "$API_URL/v1/mangas".toHttpUrl().newBuilder().apply {
            category?.ifEmpty { null }?.let { addQueryParameter("category", it) }
            genre?.ifEmpty { null }?.let { addQueryParameter("genre", it) }
            status?.ifEmpty { null }?.let { addQueryParameter("status", it) }
            addQueryParameter("sort", sort)
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "18")
        }.build()
        return GET(url, headers)
    }

    private fun parseMangaList(response: Response): MangasPage {
        val data = response.parseData<MangaListDto>()
        return MangasPage(data.items.map { it.toSManga() }, data.page < data.totalPages)
    }

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$API_URL/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query.trim())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("page_size", "20")
                .build()
            return GET(url, headers)
        }
        return mangaListRequest(
            page,
            sort = filters.firstInstanceOrNull<SortFilter>()?.value ?: "popular",
            category = filters.firstInstanceOrNull<CategoryFilter>()?.value,
            genre = filters.firstInstanceOrNull<GenreFilter>()?.value,
            status = filters.firstInstanceOrNull<StatusFilter>()?.value,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.encodedPath.endsWith("/search")) {
            val data = response.parseData<SearchDto>()
            // The index holds a series once per slug alias, all with the same mid
            return MangasPage(data.data.map { it.toSManga() }.distinctBy { it.url }, data.page < data.totalPages)
        }
        return parseMangaList(response)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("搜尋文字時不使用篩選"),
        SortFilter(),
        CategoryFilter(),
        GenreFilter(),
        StatusFilter(),
    )

    // ============================== Details ===============================

    override fun getMangaUrl(manga: SManga) = "$baseUrl/works/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$API_URL/v1/manga".toHttpUrl().newBuilder()
            .addQueryParameter("mid", manga.url)
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga = response.parseData<MangaDto>().toSManga()

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga) = chapterPageRequest(manga.url, 1)

    private fun chapterPageRequest(mid: String, page: Int): Request {
        val url = "$API_URL/v1/manga/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("mid", mid)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "50")
            .addQueryParameter("order", "desc")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val mid = response.request.url.queryParameter("mid")!!
        val first = response.parseData<ChapterListDto>()
        val rest = (2..first.totalPages).map { page ->
            client.newCall(chapterPageRequest(mid, page)).execute().parseData<ChapterListDto>()
        }
        return (listOf(first) + rest).flatMap { it.items }.map { it.toSChapter() }
    }

    override fun getChapterUrl(chapter: SChapter) = "https://$READER_HOST/chapter/${chapter.url}"

    // ============================== Pages =================================

    override fun pageListRequest(chapter: SChapter): Request {
        // Chapter hid = b64("m:<manga id>-c:<chapter id>")-<suffix>; the API takes b64("c:<chapter id>")-<suffix>
        val ids = decode(chapter.url.substringBefore('-'))
        val apiHid = encode(ids.substringAfter('-')) + "-" + chapter.url.substringAfter('-')
        val url = "$API_URL/v2/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("hid", apiHid)
            .build()
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseData<ChapterPagesDto>()

        val images = decodeImages(data.images).toMutableList()
        if (images.isNotEmpty()) decoyIndex(images.size, data.orderId, data.sid)?.let { images.removeAt(it) }

        // Line 9 is served from the reader's "s1" image hosts
        val imageBase = if (data.line == 9) IMAGE_URL_S1 else IMAGE_URL
        return images.mapIndexed { index, path -> Page(index, imageUrl = imageBase + path) }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // The site reader's page list decoding (/assets/runtime/chapter-decoder.js on the reader host):
    // "qM9" head "Vx" mid "pL0" tail "Z7", with tail = n / 3 and head = (n - tail) / 2 of the n payload chars
    private fun decodeImages(encoded: String): List<String> {
        val body = encoded.removePrefix("qM9").removeSuffix("Z7")
        val tailLength = (body.length - 5) / 3
        val headLength = (body.length - 5 - tailLength) / 2
        val head = body.take(headLength)
        val mid = body.substring(headLength + 2, body.length - 3 - tailLength)
        val tail = body.takeLast(tailLength)
        val base64 = (tail + head + mid).chunked(7)
            .mapIndexed { index, block -> if (index % 2 == 1) block.reversed() else block }
            .joinToString("")
            .map { DECODED_ALPHABET[ENCODED_ALPHABET.indexOf(it)] }
            .joinToString("")
        return decode(base64).parseAs()
    }

    // The site reader drops one entry of the list before showing the pages; BigInteger like its BigInt maths
    private fun decoyIndex(size: Int, orderId: Long, sid: Long): Int? {
        val key = (sid.toBigInteger() * DECOY_SID_FACTOR).xor(size.toBigInteger() * DECOY_SIZE_FACTOR)
            .mod(size.toBigInteger())
        return orderId.toBigInteger().xor(key).takeIf { it >= BigInteger.ZERO && it < size.toBigInteger() }?.toInt()
    }

    private fun decode(base64: String): String = String(Base64.decode(base64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))

    private fun encode(text: String): String = Base64.encodeToString(text.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private inline fun <reified T> Response.parseData(): T = parseAs<ApiResponse<T>>().let { it.data ?: throw Exception("API error: ${it.message}") }

    companion object {
        private const val API_HOST = "hipapi1.s3file.top"
        private const val API_URL = "https://$API_HOST"
        private const val READER_HOST = "reader.hipmh.top"
        private const val IMAGE_URL = "https://hip-tx-1.s3imgs.top"
        private const val IMAGE_URL_S1 = "https://hip-tx-s1.s3imgs.top"
        private const val ENCODED_ALPHABET = "_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val DECODED_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val DECOY_SID_FACTOR = BigInteger.valueOf(0x9E3779B1L)
        private val DECOY_SIZE_FACTOR = BigInteger.valueOf(0x85EBCA6BL)
    }
}
