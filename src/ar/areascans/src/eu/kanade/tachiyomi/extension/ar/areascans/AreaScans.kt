package eu.kanade.tachiyomi.extension.ar.areascans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AreaScans :
    MangaThemesia(
        "Area Scans",
        "https://ar.kenmanga.com",
        "ar",
        dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale("ar")),
    ) {

    // Site moved to a bespoke "mangareader" WP theme; listing/detail/chapter/page
    // markup no longer match the stock MangaThemesia selectors, so they're
    // overridden below based on the live HTML.

    // Listing cards: <div class="listupd"><div class="manga-card-v"><div class="card-v-cover">
    //   <a href="https://ar.kenmanga.com/manga/legend-of-star-general/"><img src="..."></a>
    //   ...<div class="card-v-body"><h3 class="card-v-title"><a href="...">Legend of Star General</a></h3>
    override fun searchMangaSelector() = ".listupd .manga-card-v"

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        thumbnail_url = element.selectFirst(".card-v-cover img")?.attr("abs:src")
        val titleLink = element.selectFirst(".card-v-title a")
        title = titleLink?.text().orEmpty()
        setUrlWithoutDomain(titleLink?.attr("href").orEmpty())
    }

    // Manga details page: the title <h1 class="manga-title-large"> lives in the hero
    // section while author/artist/status/description/genres live in a separate
    // ".manga-content-grid" section, so scope to the whole document.
    override val seriesDetailsSelector = "body"

    override val seriesTitleSelector = "h1.manga-title-large"

    override val seriesThumbnailSelector = ".manga-poster img"

    override val seriesDescriptionSelector = ".story-text"

    override val seriesGenreSelector = ".filter-tags .filter-tag"

    // e.g. <div class="info-row"><span class="info-label">الحالة</span><span class="info-val">مستمرة</span></div>
    override val seriesStatusSelector = ".info-row:contains(الحالة) .info-val"

    override val seriesAuthorSelector = ".info-row:contains(المؤلف) .info-val"

    override val seriesArtistSelector = ".info-row:contains(الرسام) .info-val"

    // Chapter list: <div class="chapters-list" id="chapters-list-container">
    //   <a href="https://ar.kenmanga.com/legend-of-star-general-chapter-293/" class="chapter-item ch-item" data-id="202835" data-ch="293">
    //     <span class="chap-num">الفصل 293</span> ... <span class="chap-date">2025/11/19</span></a>
    override fun chapterListSelector() = "div.chapters-list a.chapter-item"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst(".chap-num")?.text().orEmpty()
        date_upload = element.selectFirst(".chap-date")?.text().parseChapterDate()
    }

    // Chapter pages aren't in the chapter page HTML; they're fetched via a secure
    // AJAX endpoint keyed off a chapter id embedded in the page as
    // `var ARYA_CHAPTER_ID = 202835;`. The endpoint returns
    // {"success":true,"data":{"status":"unlocked","content":"<img src=\"...\" />..."}}
    // (or {"status":"locked","shortlink":"..."} when gated behind a shortlink).
    private val chapterIdRegex = Regex("ARYA_CHAPTER_ID\\s*=\\s*(\\d+)")

    override fun pageListParse(document: Document): List<Page> {
        val chapterId = chapterIdRegex.find(document.html())?.groupValues?.get(1)
            ?: return super.pageListParse(document)

        val form = FormBody.Builder()
            .add("action", "get_secure_chapter_images")
            .add("chapter_id", chapterId)
            .build()

        val request = POST(
            "$baseUrl/wp-admin/admin-ajax.php",
            headersBuilder().set("Referer", document.location()).build(),
            form,
        )

        val body = client.newCall(request).execute().use { it.body.string() }
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return emptyList()
        if (data["status"]?.jsonPrimitive?.content != "unlocked") return emptyList()
        val content = data["content"]?.jsonPrimitive?.content ?: return emptyList()

        val chapterUrl = document.location()
        return Jsoup.parse(content).select("img").mapIndexed { i, img -> Page(i, chapterUrl, img.attr("src")) }
    }
}
