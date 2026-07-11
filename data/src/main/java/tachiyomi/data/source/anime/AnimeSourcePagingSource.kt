package tachiyomi.data.source.anime

import androidx.paging.PagingState
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.items.episode.model.NoEpisodesException
import tachiyomi.domain.source.anime.repository.AnimeSourcePagingSourceType

class AnimeSourceSearchPagingSource(
    source: AnimeCatalogueSource,
    val query: String,
    val filters: AnimeFilterList,
) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getSearchAnime(currentPage, query, filters)
    }
}

class AnimeSourcePopularPagingSource(source: AnimeCatalogueSource) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getPopularAnime(currentPage)
    }
}

class AnimeSourceLatestPagingSource(source: AnimeCatalogueSource) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class AnimeSourcePagingSource(
    protected val source: AnimeCatalogueSource,
) : AnimeSourcePagingSourceType() {

    abstract suspend fun requestNextPage(currentPage: Int): AnimesPage

    // URLs already emitted by earlier pages of this paging session. Cursor-paginated sources can
    // re-serve the same entries across page boundaries when the pager reloads an anchor page or
    // prefetches; without this the same entry shows up twice while scrolling.
    // Guarded by its own monitor: Paging can run concurrent load() calls, and a retried page must
    // return the entries it already claimed instead of finding them all "seen" and going blank.
    private val seenUrls = HashSet<String>()
    private val pageResults = HashMap<Long, List<SAnime>>()

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, SAnime> {
        val page = params.key ?: 1

        val animesPage = try {
            withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.animes.isNotEmpty() }
                    ?: throw NoEpisodesException()
            }
        } catch (e: Exception) {
            return LoadResult.Error(e)
        }

        val data = synchronized(seenUrls) {
            pageResults.getOrPut(page) {
                animesPage.animes.filter { seenUrls.add(it.url) }
            }
        }

        return LoadResult.Page(
            data = data,
            prevKey = null,
            nextKey = if (animesPage.hasNextPage) page + 1 else null,
        )
    }

    override fun getRefreshKey(state: PagingState<Long, SAnime>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}
