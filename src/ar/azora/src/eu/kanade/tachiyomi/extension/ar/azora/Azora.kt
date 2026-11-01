package eu.kanade.tachiyomi.extension.ar.azora

import eu.kanade.tachiyomi.multisrc.iken.Iken
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Azora :
    Iken(
        "Azora",
        "ar",
        "https://azorafly.com",
        "https://api.azorafly.com",
    ) {

    override val versionId = 2

    // The post endpoint no longer embeds chapters; they have their own endpoint keyed by post id.
    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringBeforeLast("#")
        val id = manga.url.substringAfterLast("#")
        return GET("$apiUrl/api/post/chapters?postId=$id&take=$CHAPTER_LIMIT#$slug", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.fragment
        return response.parseAs<List<ChapterDto>>()
            .filter { it.chapterStatus == "PUBLIC" && it.isAccessible }
            .map { chapter ->
                SChapter.create().apply {
                    url = "/series/$slug/${chapter.slug}#${chapter.id}"
                    name = buildString {
                        append("Chapter ")
                        append(chapter.number.content)
                        if (!chapter.title.isNullOrBlank()) append(" - ").append(chapter.title)
                    }
                    date_upload = dateFormat.tryParse(chapter.createdAt)
                }
            }
            .asReversed()
    }
}

@Serializable
private class ChapterDto(
    val id: Int,
    val slug: String,
    val number: JsonPrimitive,
    val title: String? = null,
    val createdAt: String? = null,
    val chapterStatus: String? = null,
    val isAccessible: Boolean = true,
)

private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)

private const val CHAPTER_LIMIT = 10000
