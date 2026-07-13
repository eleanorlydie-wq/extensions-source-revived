package eu.kanade.tachiyomi.extension.en.mangablaze

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MangaBlaze :
    Madara(
        "MangaBlaze",
        "https://mangablaze.com",
        "en",
    ) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true

    // Popular/latest/search listings all use the same "acard" card markup, e.g.:
    // <a class="acard" href="https://mangablaze.com/manga/isekai-nonbiri-nouka/">
    //   <div class="ac-img">...<img loading="lazy" src="https://.../cover.jpg" alt="..."></div>
    //   <div class="ac-t">Isekai Nonbiri Nouka</div>
    // </a>
    override fun popularMangaSelector() = "a.acard"
    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(".ac-t")!!.text()
        thumbnail_url = element.selectFirst(".ac-img img")?.let { processThumbnail(imageFromElement(it), true) }
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun parseGenres(document: Document): List<Genre> = document.select("label.r9-chip:has(input[type=checkbox][name='genre[]'])").map {
        Genre(
            it.selectFirst("span")!!.text(),
            it.selectFirst("input")!!.`val`(),
        )
    }

    // Manga details page markup, e.g.:
    // <h1 class="htitle">Isekai Nonbiri Nouka</h1>
    // <div class="poster"><a ...><img src="https://.../cover.jpg" alt="..."></a></div>
    // <div class="syn clamp" id="syn">Having died of sickness...</div>
    // <div class="genres"><a class="genre" href=".../manga-genre/adventure/">adventure</a>...</div>
    override val mangaDetailsSelectorTitle = "h1.htitle"
    override val mangaDetailsSelectorThumbnail = ".poster img"
    override val mangaDetailsSelectorDescription = ".syn"
    override val mangaDetailsSelectorGenre = ".genres a.genre"

    override fun chapterListSelector() = "a.nxv3-card:not(.zax-chapter-premium)"
    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        name = element.selectFirst(".zax-chapter-title")!!.text()
    }

    // The manga details page no longer renders a static chapter list: chapters are
    // embedded as a JS array inside a <script> tag, e.g.:
    // var CH=[{"id":8165,"label":"Chapter 328","url":"https:\/\/mangablaze.com\/manga\/isekai-nonbiri-nouka\/chapter-328\/","ago":"2 weeks ago",...},...];
    // Fall back to the old selector-based parsing if that script is ever absent.
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val chJson = document.select("script")
            .firstOrNull { it.data().contains("var CH=") }
            ?.data()
            ?.let { CH_ARRAY_REGEX.find(it)?.groupValues?.get(1) }

        val chapters = chJson?.let {
            runCatching { json.decodeFromString<List<MangaBlazeChapterDto>>(it) }.getOrNull()
        }

        if (!chapters.isNullOrEmpty()) {
            return chapters.map { chapter ->
                SChapter.create().apply {
                    setUrlWithoutDomain(chapter.url)
                    name = chapter.label
                    date_upload = chapter.ago?.let(::parseRelativeDate) ?: 0L
                }
            }
        }

        return document.select(chapterListSelector()).map(::chapterFromElement)
    }

    @Serializable
    class MangaBlazeChapterDto(
        val label: String,
        val url: String,
        val ago: String? = null,
    )

    companion object {
        private val CH_ARRAY_REGEX = Regex("""var CH=(\[.*?\]);""")
    }
}
