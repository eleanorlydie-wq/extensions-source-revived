package eu.kanade.tachiyomi.extension.tr.nemesisscans

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SeriesListResponseDto(
    val data: List<SeriesDto> = emptyList(),
    val pagination: PaginationDto = PaginationDto(),
)

@Serializable
class PaginationDto(
    val page: Int = 1,
    val pages: Int = 1,
)

@Serializable
class SeriesDetailResponseDto(
    val series: SeriesDto,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
class SeriesDto(
    val title: TitleDto,
    @SerialName("_id") val id: Int,
    val poster: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
)

@Serializable
class TitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
class EpisodeDto(
    val episodeNumber: Double,
    val episodeName: String? = null,
    val updatedAt: String? = null,
)

@Serializable
class EpisodeDetailResponseDto(
    val episode: EpisodeContentDto,
)

@Serializable
class EpisodeContentDto(
    val images: List<ImageDto> = emptyList(),
)

@Serializable
class ImageDto(
    val url: String,
    val order: Int = 0,
)
