package eu.kanade.tachiyomi.extension.vi.minotruyen

import kotlinx.serialization.Serializable

@Serializable
class BooksResponse(
    val data: BooksData,
    val meta: BooksMeta? = null,
)

@Serializable
class BooksData(
    val books: List<Book> = emptyList(),
)

@Serializable
class BooksMeta(
    val page: Int? = null,
    val take: Int? = null,
    val itemCount: Int? = null,
    val pageCount: Int? = null,
)

@Serializable
class BookDetailResponse(
    val data: BookDetailData,
)

@Serializable
class BookDetailData(
    val book: BookDetail,
)

@Serializable
class ChaptersResponse(
    val data: ChaptersData,
)

@Serializable
class ChaptersData(
    val chapters: List<Chapter> = emptyList(),
)

@Serializable
class ChapterDetailResponse(
    val data: ChapterDetailData,
)

@Serializable
class ChapterDetailData(
    val chapter: ChapterContent? = null,
)

@Serializable
class ChapterContent(
    val images: List<ChapterImagePage> = emptyList(),
)

@Serializable
class Book(
    val bookId: Int,
    val category: String? = null,
    val info: BookInfo? = null,
    val cover: BookCover? = null,
)

@Serializable
class BookDetail(
    val bookId: Int,
    val category: String? = null,
    val description: String? = null,
    val authors: List<AuthorWrapper> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val info: BookInfo? = null,
    val cover: BookCover? = null,
)

@Serializable
class BookInfo(
    val title: String? = null,
)

@Serializable
class BookCover(
    val imageUrl: String? = null,
)

@Serializable
class AuthorWrapper(
    val author: Author,
)

@Serializable
class Author(
    val name: String? = null,
)

@Serializable
class Tag(
    val name: String,
)

@Serializable
class Chapter(
    val chapterId: Int,
    val chapterNumber: String? = null,
    val title: String? = null,
    val createdAt: String? = null,
)

@Serializable
class ChapterImagePage(
    val order: Int? = null,
    val servers: List<ImageServer> = emptyList(),
)

@Serializable
class ImageServer(
    val imageUrl: String,
    val drmData: String? = null,
)
