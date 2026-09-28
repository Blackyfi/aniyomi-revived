package eu.kanade.tachiyomi.data.track.myanimelist.dto

import kotlinx.serialization.Serializable

// Search and user-list responses share this shape. The nodes carry the full entry because the
// requests ask for its fields up front, so no per-result details request is needed.

@Serializable
data class MALMangaSearchResult(
    val data: List<MALMangaSearchNode>,
    val paging: MALSearchPaging? = null,
)

@Serializable
data class MALMangaSearchNode(
    val node: MALManga,
)

@Serializable
data class MALAnimeSearchResult(
    val data: List<MALAnimeSearchNode>,
    val paging: MALSearchPaging? = null,
)

@Serializable
data class MALAnimeSearchNode(
    val node: MALAnime,
)

@Serializable
data class MALSearchPaging(
    val next: String? = null,
)
