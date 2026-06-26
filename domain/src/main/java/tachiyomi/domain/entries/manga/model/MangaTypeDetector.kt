package tachiyomi.domain.entries.manga.model

/**
 * Heuristic that guesses whether an entry is a manhwa from the data extensions already provide:
 * the entry's genres/tags and the source's language. It deliberately errs towards [MangaType.MANHWA]
 * for ambiguous Korean/Chinese content - the user can always correct it manually, and a manual
 * choice is preserved (auto-detection only runs while the type is still [MangaType.UNKNOWN]).
 */
object MangaTypeDetector {

    private val MANHWA_GENRE_KEYWORDS = listOf(
        "manhwa",
        "manhua",
        "webtoon",
        "web comic",
        "webcomic",
        "long strip",
        "longstrip",
        "wuxia",
    )

    // Source language codes whose catalogues are predominantly manhwa/manhua.
    private val MANHWA_SOURCE_LANGS = setOf(
        "ko",
        "zh",
        "zh-hans",
        "zh-hant",
        "zh-rcn",
        "zh-rtw",
        "zh-rhk",
    )

    fun detect(genres: List<String>?, sourceLang: String?): MangaType {
        val matchesGenre = genres.orEmpty().any { rawGenre ->
            val genre = rawGenre.lowercase().trim()
            MANHWA_GENRE_KEYWORDS.any { keyword -> genre.contains(keyword) }
        }
        if (matchesGenre) return MangaType.MANHWA

        if (sourceLang != null && sourceLang.lowercase() in MANHWA_SOURCE_LANGS) {
            return MangaType.MANHWA
        }

        return MangaType.MANGA
    }
}
