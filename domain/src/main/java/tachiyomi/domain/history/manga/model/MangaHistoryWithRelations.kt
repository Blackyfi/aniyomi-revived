package tachiyomi.domain.history.manga.model

import tachiyomi.domain.entries.manga.model.MangaCover
import tachiyomi.domain.entries.manga.model.MangaType
import java.util.Date

data class MangaHistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val title: String,
    val chapterNumber: Double,
    val readAt: Date?,
    val readDuration: Long,
    val coverData: MangaCover,
    val mangaType: MangaType = MangaType.UNKNOWN,
)
