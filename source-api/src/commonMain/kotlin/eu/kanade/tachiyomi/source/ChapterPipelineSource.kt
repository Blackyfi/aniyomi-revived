package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SManga

/**
 * A source whose server does work on a chapter *after* it appears in the list —
 * fetching its pages, then upscaling them — and can report how far along each
 * chapter is.
 *
 * This is deliberately not carried on [eu.kanade.tachiyomi.source.model.SChapter].
 * The fields there feed chapter identity and the on-disk download folder name
 * (`getChapterDirName(name, scanlator)`), so a value that flips as the server
 * works would rename folders under downloads that already exist. The state is
 * fetched for display instead, and never stored.
 *
 * Performs blocking network I/O — always call off the main thread (e.g. inside
 * `withIOContext`). Intentionally not `suspend`: extensions are compiled without
 * kotlinx-coroutines.
 */
interface ChapterPipelineSource : MangaSource {

    /**
     * Per-chapter server-side progress for [manga], keyed by the same
     * `SChapter.url` the source hands back from `getChapterList` (i.e. already
     * domain-stripped, so it matches the stored `Chapter.url`).
     *
     * Chapters the server knows nothing about are simply absent from the map;
     * callers show no indicator for those rather than a false negative.
     */
    fun getChapterPipelineStates(manga: SManga): Map<String, ChapterPipelineState>
}

/**
 * How far the source's server has taken one chapter.
 *
 * Note [serverDownloaded] is about the *server* holding the pages, which is a
 * different question from whether this device has the chapter downloaded — the
 * app tracks that itself.
 */
data class ChapterPipelineState(
    val serverDownloaded: Boolean = false,
    val serverUpscaled: Boolean = false,
)
