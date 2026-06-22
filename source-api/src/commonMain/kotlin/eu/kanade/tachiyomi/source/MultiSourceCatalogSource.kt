package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * A source where one library entry is backed by several interchangeable upstream
 * sources (e.g. a self-hosted server that scrapes the same series from multiple
 * sites). The app shows a source picker on the manga screen and lets the user
 * switch which source's chapters are displayed.
 *
 * Both methods perform blocking network I/O — always call them off the main
 * thread (e.g. inside `withIOContext`). They are intentionally NOT `suspend`
 * because extensions are compiled without kotlinx-coroutines.
 *
 * The chosen source is persisted server-side, so it stays in sync across the app
 * and any web UI; there is no per-manga state to store in the app.
 */
interface MultiSourceCatalogSource : MangaSource {

    /** All sources available for [manga] (primary + alternates). */
    fun getMangaSources(manga: SManga): List<MangaSourceInfo>

    /**
     * Switch the active/default source for [manga].
     *
     * @param sourceKey one of the [MangaSourceInfo.key] values, or "auto".
     * @return the refreshed source list.
     */
    fun setMangaSource(manga: SManga, sourceKey: String): List<MangaSourceInfo>

    /**
     * Chapters for a specific [sourceKey] WITHOUT changing the active source. Lets the app prefetch
     * alternate sources and switch between them instantly (no waiting on [setMangaSource]).
     *
     * The default returns an empty list; callers must fall back to the normal effective-source
     * chapter list (via [setMangaSource] + [getChapterList]) when that happens, so sources that
     * don't support per-source reads keep working.
     *
     * @param sourceKey one of the [MangaSourceInfo.key] values, or "auto".
     */
    fun getChapterListForSource(manga: SManga, sourceKey: String): List<SChapter> = emptyList()
}

/** One selectable source for a manga. */
data class MangaSourceInfo(
    val key: String,
    val name: String,
    val totalChapters: Int = 0,
    val latestChapter: Float = 0f,
    val isPrimary: Boolean = false,
    val isDefault: Boolean = false,
    val isMostUpToDate: Boolean = false,
    val isEffective: Boolean = false,
)
