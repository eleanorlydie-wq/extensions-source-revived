package eu.kanade.tachiyomi.extension.es.mangashiina

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.text.SimpleDateFormat

/**
 * DTOs for MangaMukai's custom JSON API, served under
 * https://mangamukai.com/wp-json/mangamukai/v1/
 *
 * The site itself is now a Vite/React SPA (index.html only contains a
 * `<div id="root">`); all real content is fetched by the bundled JS from
 * this API. Field names/shapes below were captured directly from live
 * responses of that API (see evidence in the audit notes), not guessed.
 */

// GET /catalog -> { success, total, mangas: [...] }
@Serializable
class CatalogResponseDto(
    val success: Boolean = false,
    val mangas: List<CatalogMangaDto> = emptyList(),
)

@Serializable
class CatalogMangaDto(
    val id: Long,
    val titulo: String = "",
    val portada: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("latest_update_at") val latestUpdateAt: String? = null,
    val fecha: String? = null,
    // Non-empty only when the series actually has a published chapter.
    // Used to rank browsable (readable) titles first.
    val capitulosRecientes: List<JsonElement> = emptyList(),
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = "/wp-json/mangamukai/v1/manga/$id"
        title = titulo
        thumbnail_url = portada?.replace("http://", "https://")
    }
}

// GET /manga/{id} -> { success, id, titulo, portada, descripcion, genres, status, tipo, ... }
@Serializable
class MangaDetailsDto(
    val success: Boolean = false,
    val titulo: String = "",
    val portada: String? = null,
    val descripcion: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = titulo
        thumbnail_url = portada?.replace("http://", "https://")
        description = descripcion?.trim()
        genre = genres.joinToString(", ").ifEmpty { null }
        status = when {
            this@MangaDetailsDto.status == null -> SManga.UNKNOWN
            this@MangaDetailsDto.status.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            this@MangaDetailsDto.status.contains("complet", ignoreCase = true) -> SManga.COMPLETED
            this@MangaDetailsDto.status.contains("cancel", ignoreCase = true) -> SManga.CANCELLED
            this@MangaDetailsDto.status.contains("hiatus", ignoreCase = true) ||
                this@MangaDetailsDto.status.contains("pausa", ignoreCase = true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        initialized = true
    }
}

// GET /series/{id}/chapters -> { success, chapters: [...] }
@Serializable
class ChaptersResponseDto(
    val success: Boolean = false,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val id: Long,
    @SerialName("chapter_number") val chapterNumber: Double = -1.0,
    val title: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toSChapter(dateFormat: SimpleDateFormat): SChapter = SChapter.create().apply {
        url = "/wp-json/mangamukai/v1/chapters/content?id=$id"
        name = title?.takeIf { it.isNotBlank() } ?: "Capítulo $chapterNumber"
        chapter_number = chapterNumber.toFloat()
        date_upload = createdAt?.let {
            runCatching { dateFormat.parse(it)?.time }.getOrNull()
        } ?: 0L
    }
}

// GET /chapters/content?id={chapterId} -> { success, images: [...], is_paid }
@Serializable
class ChapterContentDto(
    val success: Boolean = false,
    val images: List<String> = emptyList(),
)
