package eu.kanade.tachiyomi.extension.ko.newxtoon

import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
class ChapterListDto(
    val chapters: List<ChapterDto>,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
class ChapterDto(
    private val id: Long,
    private val title: String,
    private val date: String? = null,
) {
    fun toSChapter(mangaId: String) = SChapter.create().apply {
        url = "/comics/$mangaId/chapters/$id"
        name = title
        date_upload = dateFormat.tryParse(date)
    }
}

private val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("Asia/Seoul")
}
