package eu.kanade.tachiyomi.extension.en.warforrayuba.dto

import kotlinx.serialization.Serializable

@Serializable
data class GithubContentDto(
    val name: String,
    val download_url: String,
)
