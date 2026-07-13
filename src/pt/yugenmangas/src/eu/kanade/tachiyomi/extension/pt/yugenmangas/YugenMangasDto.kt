package eu.kanade.tachiyomi.extension.pt.yugenmangas

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class LibraryResponse(
    val items: List<SeriesSummaryDto> = emptyList(),
    val page: Int = 1,
    @SerialName("per_page")
    val perPage: Int = 1,
    val total: Int = 0,
) {
    fun hasNextPage() = page * perPage < total
}

@Serializable
class SearchResponse(
    val series: List<SeriesSummaryDto> = emptyList(),
)

@Serializable
class SeriesSummaryDto(
    val identifier: String,
    val title: String = "",
    val cover: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        title = this@SeriesSummaryDto.title
        thumbnail_url = cover
        url = "/series/$identifier"
    }
}

@Serializable
class UpdatesResponse(
    val items: List<UpdateItemDto> = emptyList(),
    @SerialName("has_more")
    val hasMore: Boolean = false,
)

@Serializable
class UpdateItemDto(
    @SerialName("series_identifier")
    val seriesIdentifier: String,
    @SerialName("series_title")
    val seriesTitle: String = "",
    @SerialName("series_cover")
    val seriesCover: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        title = seriesTitle
        thumbnail_url = seriesCover
        url = "/series/$seriesIdentifier"
    }
}

@Serializable
class NamedDto(
    val name: String = "",
)

@Serializable
class SeriesDetailDto(
    val identifier: String,
    val title: String = "",
    val cover: String? = null,
    val synopsis: String? = null,
    val status: String? = null,
    val authors: List<NamedDto> = emptyList(),
    val artists: List<NamedDto> = emptyList(),
    val genres: List<NamedDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        title = this@SeriesDetailDto.title
        thumbnail_url = cover
        description = synopsis
        author = authors.joinToString { it.name }.takeIf(String::isNotBlank)
        artist = artists.joinToString { it.name }.takeIf(String::isNotBlank)
        genre = genres.joinToString { it.name }.takeIf(String::isNotBlank)
        url = "/series/$identifier"
        status = when (this@SeriesDetailDto.status) {
            "ongoing" -> SManga.ONGOING
            "finished" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "canceled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class ChaptersResponse(
    val items: List<ChapterItemDto> = emptyList(),
    @SerialName("has_more")
    val hasMore: Boolean = false,
)

@Serializable
class ChapterItemDto(
    val identifier: String,
    val number: Double = 0.0,
    @SerialName("published_at")
    val publishedAt: String? = null,
)

@Serializable
class ChapterDetailDto(
    val pages: List<ChapterPageDto> = emptyList(),
)

@Serializable
class ChapterPageDto(
    val number: Int = 0,
    val url: String,
)
