package eu.kanade.tachiyomi.ui.updates.manga

import eu.kanade.presentation.updates.manga.MangaUpdatesUiModel
import eu.kanade.tachiyomi.data.download.manga.model.MangaDownload
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.manga.model.MangaCover
import tachiyomi.domain.updates.manga.model.MangaUpdatesWithRelations
import java.time.LocalDate
import java.time.ZoneId

class MangaUpdatesUiModelTest {

    private fun updateItem(chapterId: Long, day: LocalDate): MangaUpdatesItem {
        val dateFetch = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return MangaUpdatesItem(
            update = MangaUpdatesWithRelations(
                mangaId = chapterId,
                mangaTitle = "Manga $chapterId",
                chapterId = chapterId,
                chapterName = "Chapter $chapterId",
                scanlator = null,
                read = false,
                bookmark = false,
                lastPageRead = 0L,
                sourceId = 1L,
                dateFetch = dateFetch,
                coverData = MangaCover(
                    mangaId = chapterId,
                    sourceId = 1L,
                    isMangaFavorite = true,
                    url = null,
                    lastModified = 0L,
                ),
            ),
            downloadStateProvider = { MangaDownload.State.NOT_DOWNLOADED },
            downloadProgressProvider = { 0 },
        )
    }

    private fun uiModelOf(vararg items: MangaUpdatesItem) =
        MangaUpdatesScreenModel.State(items = items.toList().toPersistentList()).getUiModel()

    private val day1 = LocalDate.of(2026, 8, 30)
    private val day2 = LocalDate.of(2026, 8, 31)

    @Test
    @DisplayName("One header per date group, keyed by the item it introduces")
    fun headersAreGrouped() {
        val uiModel = uiModelOf(
            updateItem(1L, day2),
            updateItem(2L, day2),
            updateItem(3L, day1),
        )

        val headers = uiModel.filterIsInstance<MangaUpdatesUiModel.Header>()
        headers shouldHaveSize 2
        headers.map { it.date } shouldBe listOf(day2, day1)
        headers.map { it.anchorChapterId } shouldBe listOf(1L, 3L)
    }

    @Test
    @DisplayName("Header keys stay unique even when the same date heads several groups")
    fun headerKeysAreUniqueForRepeatedDates() {
        // Rows arriving out of date order used to produce two Header(day2) instances. They
        // hashed identically, so the Updates LazyColumn saw a duplicate key and crashed with
        // IllegalArgumentException. The query now orders explicitly, but the key must not
        // depend on that being true.
        val uiModel = uiModelOf(
            updateItem(1L, day2),
            updateItem(2L, day1),
            updateItem(3L, day2),
        )

        val headers = uiModel.filterIsInstance<MangaUpdatesUiModel.Header>()
        headers shouldHaveSize 3
        headers.map { it.date } shouldBe listOf(day2, day1, day2)
        headers.map { it.anchorChapterId }.distinct() shouldHaveSize 3
    }

    @Test
    @DisplayName("Every entry in the list has a distinct key")
    fun allKeysAreDistinct() {
        val uiModel = uiModelOf(
            updateItem(1L, day2),
            updateItem(2L, day1),
            updateItem(3L, day2),
            updateItem(4L, day1),
        )

        // Mirrors the keys built in MangaUpdatesUiItem.mangaUpdatesUiItems.
        val keys = uiModel.map {
            when (it) {
                is MangaUpdatesUiModel.Header -> "mangaUpdatesHeader-${it.anchorChapterId}"
                is MangaUpdatesUiModel.Item ->
                    "mangaUpdates-${it.item.update.mangaId}-${it.item.update.chapterId}"
            }
        }

        keys.distinct() shouldHaveSize keys.size
    }

    @Test
    @DisplayName("An empty update list produces no rows")
    fun emptyList() {
        uiModelOf() shouldHaveSize 0
    }
}
