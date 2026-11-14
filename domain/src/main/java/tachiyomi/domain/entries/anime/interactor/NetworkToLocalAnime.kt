package tachiyomi.domain.entries.anime.interactor

import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager

class NetworkToLocalAnime(
    private val animeRepository: AnimeRepository,
    private val sourceManager: AnimeSourceManager,
) {

    suspend fun await(anime: Anime): Anime {
        val localAnime = getAnime(anime.url, anime.source)
        return when {
            localAnime == null -> {
                val id = insertAnime(anime)
                anime.copy(id = id!!)
            }
            !localAnime.favorite -> {
                // if the anime isn't a favorite, set its display title from source
                // if it later becomes a favorite, updated title will go to db
                refreshThumbnail(localAnime, anime).copy(title = anime.title)
            }
            else -> {
                refreshThumbnail(localAnime, anime)
            }
        }
    }

    /**
     * Browse shows the stored row, so a thumbnail saved while a source was broken (blank) or
     * under a site's old layout (dead URL) would otherwise stick until the entry is opened.
     * Take the source's current thumbnail for entries outside the library, and for library
     * entries only when they have none (their cover is disk-cached and refreshed via details).
     */
    private suspend fun refreshThumbnail(localAnime: Anime, anime: Anime): Anime {
        val remote = anime.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return localAnime
        if (remote == localAnime.thumbnailUrl) return localAnime
        if (localAnime.favorite && !localAnime.thumbnailUrl.isNullOrBlank()) return localAnime
        val coverLastModified = System.currentTimeMillis()
        animeRepository.updateAnime(
            AnimeUpdate(id = localAnime.id, thumbnailUrl = remote, coverLastModified = coverLastModified),
        )
        return localAnime.copy(thumbnailUrl = remote, coverLastModified = coverLastModified)
    }

    private suspend fun getAnime(url: String, sourceId: Long): Anime? {
        return animeRepository.getAnimeByUrlAndSourceId(url, sourceId)
    }

    private suspend fun insertAnime(anime: Anime): Long? {
        return animeRepository.insertAnime(anime)
    }
}
