package eu.kanade.tachiyomi.extension.all.mangaball

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SearchResponse(
    val data: List<Title>,
    private val pagination: Pagination,
) {
    @Serializable
    class Pagination(
        val page: Int,
        @SerialName("total_pages")
        val totalPages: Int,
    )

    fun hasNextPage() = pagination.page < pagination.totalPages
}

@Serializable
class DetailResponse(
    val data: Title,
)

@Serializable
class Title(
    val id: String,
    val slug: String = "",
    val name: String? = null,
    val image: Image? = null,
    val description: List<String> = emptyList(),
    val alternateName: List<String> = emptyList(),
    val author: List<Named> = emptyList(),
    val tags: List<Named> = emptyList(),
    val status: String? = null,
    @SerialName("date_published")
    val published: Int? = null,
    val originalLanguage: String? = null,
)

@Serializable
class Image(
    private val file: String? = null,
    private val cover: Cover? = null,
) {
    @Serializable
    class Cover(val path: String? = null)

    val url get() = cover?.path?.let { COVER_BASE_URL + it } ?: file?.takeIf { it.isNotBlank() }
}

@Serializable
class Named(
    val name: String? = null,
)

@Serializable
class ChapterListResponse(
    val data: List<Chapter>,
)

@Serializable
class Chapter(
    val id: String,
    val lang: String,
    val name: String? = null,
    val number: Float? = null,
    val volume: Float? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("group_name")
    val groupName: String? = null,
)

private const val COVER_BASE_URL = "https://bulbasaur.poke-black-and-white.net/covers/"
