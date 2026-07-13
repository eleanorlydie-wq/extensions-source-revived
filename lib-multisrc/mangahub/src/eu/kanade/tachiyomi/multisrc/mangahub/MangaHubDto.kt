package eu.kanade.tachiyomi.multisrc.mangahub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias ApiChapterPagesResponse = ApiResponse<ApiChapterData>
typealias ApiSearchResponse = ApiResponse<ApiSearchObject>
typealias ApiMangaDetailsResponse = ApiResponse<ApiMangaObject>

// Base classes
@Serializable
class ApiResponse<T>(
    val data: T,
)

@Serializable
class ApiResponseError(
    val errors: List<ApiErrorMessages>? = null,
)

@Serializable
class ApiErrorMessages(
    val message: String,
)

@Serializable
class PublicIPResponse(
    val ip: String,
)

// Chapter metadata (pages)
@Serializable
class ApiChapterData(
    val chapter: ApiChapter,
)

@Serializable
class ApiChapter(
    val pages: String,
    val mangaID: Int,
    @SerialName("number") val chapterNumber: Float,
    val manga: ApiMangaData,
)

@Serializable
class ApiChapterPages(
    @SerialName("p") val page: String,
    @SerialName("i") val images: List<String>,
)

// Search, Popular, Latest
@Serializable
class ApiSearchObject(
    val search: ApiSearchResults,
)

@Serializable
class ApiSearchResults(
    val rows: List<ApiMangaSearchItem>,
)

@Serializable
class ApiMangaSearchItem(
    val title: String,
    val slug: String,
    val image: String,
    val author: String,
    val latestChapter: Float,
    val genres: String,
)

// Manga Details, Chapters
@Serializable
class ApiMangaObject(
    val manga: ApiMangaData,
)

// Nullable is not the same as optional: without a default, kotlinx.serialization throws
// MissingFieldException when a key is absent rather than present-and-null. Each GraphQL query here
// selects a different subset of these fields (the details query doesn't ask for `chapters` at all),
// so anything not defaulted blows up as soon as a query omits it.
@Serializable
class ApiMangaData(
    val title: String? = null,
    val status: String? = null,
    val image: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: String? = null,
    val description: String? = null,
    val alternativeTitle: String? = null,
    val slug: String? = null,
    val chapters: List<ApiMangaChapterList>? = null,
)

@Serializable
class ApiMangaChapterList(
    val number: Float = 0f,
    val title: String = "",
    val date: String = "",
)
