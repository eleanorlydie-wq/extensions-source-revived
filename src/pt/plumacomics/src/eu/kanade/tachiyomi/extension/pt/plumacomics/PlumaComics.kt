package eu.kanade.tachiyomi.extension.pt.plumacomics

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class PlumaComics : HttpSource() {

    override val name: String = "Pluma Comics"

    override val lang: String = "pt-BR"

    override val baseUrl: String = "https://plumacomics.cloud"

    override val supportsLatest: Boolean = true

    override val client = super.client.newBuilder()
        .rateLimit(3, 1.seconds)
        .build()

    override val versionId = 6

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)

    // Popular
    // The site migrated from Madara HTML to a Next.js SPA and "/series" now redirects
    // unauthenticated requests to "/login". The manga grid is fetched by the client from
    // this JSON API instead (seen live at https://plumacomics.cloud/api/obras?page=1&sort=popular).
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/obras?page=$page&sort=popular", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<ObrasDto>()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val mangas = dto.series.map {
            SManga.create().apply {
                title = it.title
                thumbnail_url = "$baseUrl/api/cover/${it.coverPath}"
                url = "/title/${it.slug}"
            }
        }
        return MangasPage(mangas, hasNextPage = page < dto.totalPages)
    }

    // Latest

    // Omitting "sort" defaults to newest chapter updates first (verified against the
    // homepage's "latest updates" list, which uses the same ordering).
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/obras?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()
        val mangas = dto.results.map {
            SManga.create().apply {
                title = it.title
                thumbnail_url = "$baseUrl/api/cover/${it.coverPath}"
                url = "/title/${it.slug}"
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    // Details

    // The details page ("/title/{slug}") is still server-rendered with real markup (unlike
    // "/series/*", which now redirects to "/login"); only the selectors changed with the redesign.
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")!!.text()
            thumbnail_url = document.selectFirst("img[loading=eager]")?.absUrl("src")
            description = document.selectFirst("p.whitespace-pre-line")?.text()
            genre = document.select("a[href*=genre]").joinToString { it.text() }
            setUrlWithoutDomain(document.location())
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("li a[href*=\"/view/\"]").map { element ->
            SChapter.create().apply {
                name = element.selectFirst("strong")!!.text()
                date_upload = dateFormat.tryParse(element.selectFirst("time[datetime]")?.attr("datetime"))
                setUrlWithoutDomain(element.absUrl("href"))
            }
        }
    }

    // Pages

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val chapter = document.extractNextJs<ChapterDto>() ?: throw IOException("Capítulo não encontrado")

        val response = client.newCall(
            GET(
                "$baseUrl/api/viewer/bootstrap?c=${chapter.chapterId}",
                headers,
            ),
        ).execute()

        val pages = response.parseAs<PagesList>()

        return pages.pages.map { page ->
            // The reader API now returns absolute CDN URLs (https://cdn.orionmanhuas.com/...)
            // instead of paths relative to baseUrl.
            val imageUrl = if (page.u.startsWith("http", ignoreCase = true)) {
                page.u
            } else {
                "$baseUrl/${page.u.trim('/')}"
            }
            Page(page.i, imageUrl = imageUrl)
        }
    }

    // The image CDN (cdn.orionmanhuas.com) 403s without a Referer pointing back at the site.
    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder().set("Referer", "$baseUrl/").build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    override fun imageUrlParse(response: Response): String = ""
}
