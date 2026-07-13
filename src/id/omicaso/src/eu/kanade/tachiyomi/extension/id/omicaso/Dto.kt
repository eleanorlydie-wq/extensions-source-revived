package eu.kanade.tachiyomi.extension.id.omicaso

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class MangaListResponseDto(
    val items: List<MangaItemDto> = emptyList(),
    val has_more: Boolean = false,
)

@Serializable
class MangaItemDto(
    val title: String = "",
    val url: String = "",
    val cover: String? = null,
    val description: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        title = this@MangaItemDto.title
        url = this@MangaItemDto.url
        thumbnail_url = cover
        description = this@MangaItemDto.description
    }
}
