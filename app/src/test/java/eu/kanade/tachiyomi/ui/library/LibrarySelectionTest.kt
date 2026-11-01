package eu.kanade.tachiyomi.ui.library

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibrarySelectionTest {

    private data class Item(val id: Long, val unread: Int = 0)

    private val items = listOf(Item(1), Item(2), Item(3), Item(4))

    @Test
    fun `selects forwards and backwards inclusive`() {
        itemsBetween(items, Item::id, 2L, 4L).map { it.id } shouldBe listOf(2L, 3L, 4L)
        itemsBetween(items, Item::id, 4L, 2L).map { it.id } shouldBe listOf(2L, 3L, 4L)
    }

    @Test
    fun `same entry selects only itself`() {
        itemsBetween(items, Item::id, 3L, 3L).map { it.id } shouldBe listOf(3L)
    }

    @Test
    fun `entry changed by an update still matches by id`() {
        val updated = items.map { if (it.id == 2L) it.copy(unread = 5) else it }
        itemsBetween(updated, Item::id, 2L, 3L).map { it.id } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `hidden anchor falls back to the tapped entry`() {
        itemsBetween(items, Item::id, 99L, 3L).map { it.id } shouldBe listOf(3L)
    }

    @Test
    fun `hidden tapped entry adds nothing`() {
        itemsBetween(items, Item::id, 2L, 99L).shouldBeEmpty()
    }

    @Test
    fun `mean is null without scores and never NaN`() {
        meanTrackerScore(emptyList()) shouldBe null
        meanTrackerScore(listOf(4.0, 8.0)) shouldBe 6.0
    }
}
