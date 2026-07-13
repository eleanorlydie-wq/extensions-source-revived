package eu.kanade.tachiyomi.extension.en.plutoscans

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
class NewBuildID(
    val buildId: String,
)

@Serializable
class MangaDetailsResponseData(
    val pageProps: PageProps,
) {
    @Serializable
    class PageProps(
        val series: Series,
    )
}

@Serializable
class ChapterListResponseData(
    val pageProps: PageProps,
) {
    @Serializable
    class PageProps(
        val chapters: List<Chapter> = emptyList(),
    )

    @Serializable
    data class Chapter(
        val chapter: Double,
        val title: String? = null,
        val release_date: Long = 0L,
        val series_id: Int,
        val token: String,
    )
}

@Serializable
class SearchPageData(
    val pageProps: PageProps,
) {
    @Serializable
    class PageProps(
        val series: List<Series> = emptyList(),
    )
}

@Serializable
class LatestPageData(
    val pageProps: PageProps,
) {
    @Serializable
    class PageProps(
        val latestEntries: LatestEntries,
    ) {
        @Serializable
        class LatestEntries(
            val blocks: List<Block> = emptyList(),
        ) {
            @Serializable
            class Block(
                val series: List<Series> = emptyList(),
            )
        }
    }
}

@Serializable
class ChapterPageData(
    val pageProps: PageProps,
) {
    @Serializable
    class PageProps(
        val chapter: ChapterPage,
    )

    @Serializable
    data class ChapterPage(
        val release_date: Long = 0L,
        val series_id: Int,
        val token: String,
        @Serializable(with = KeysToListSerializer::class)
        val images: List<PageImage> = emptyList(),
    )
}

// Fields observed on both the "series detail" ("tags") and "browse/latest"
// ("categories") payload shapes; both are optional since either can be absent.
@Serializable
class Series(
    val title: String,
    val altTitles: List<String>? = null,
    val description: String? = null,
    val cover: String,
    val type: String? = null,
    val tags: List<String>? = null,
    val categories: List<String>? = null,
    val author: List<String>? = null,
    val artist: List<String>? = null,
    val status: String? = null,
    val series_id: Int? = null,
    val last_edit: Long = 0L,
    val views: Int? = null,
    val likes: Int? = null,
    val year: Int? = null,
)

@Serializable
class PageImage(
    val name: String,
)

class KeysToListSerializer : KSerializer<List<PageImage>> {
    private val listSer = MapSerializer(String.serializer(), PageImage.serializer())
    override val descriptor: SerialDescriptor = listSer.descriptor
    override fun deserialize(decoder: Decoder): List<PageImage> = listSer.deserialize(decoder).map { it.value }

    override fun serialize(encoder: Encoder, value: List<PageImage>) {}
}
