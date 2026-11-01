package eu.kanade.tachiyomi.extension.es.mantrazscan

import kotlinx.serialization.Serializable

@Serializable
class ChapterDatesDto(
    val dates: Map<String, Long>,
)
