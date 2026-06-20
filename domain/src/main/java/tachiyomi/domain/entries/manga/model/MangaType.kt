package tachiyomi.domain.entries.manga.model

/**
 * Classifies a library entry as a regular manga or a manhwa (used here as an umbrella term that
 * also covers manhua, webtoons, etc. - see the issue "Manhwa Full Feature").
 *
 * [UNKNOWN] means the entry has not been classified yet. It is treated as a manga everywhere in the
 * UI until auto-detection or the user assigns a concrete value. Auto-detection only ever runs while
 * the value is [UNKNOWN], so a manual choice is never overwritten by a later refresh.
 *
 * Stored in the database as the [id] long (see `mangas.manga_type`).
 */
enum class MangaType(val id: Long) {
    UNKNOWN(0L),
    MANGA(1L),
    MANHWA(2L),
    ;

    companion object {
        fun fromId(id: Long): MangaType = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}
