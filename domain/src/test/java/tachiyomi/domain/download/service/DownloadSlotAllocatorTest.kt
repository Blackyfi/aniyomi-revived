package tachiyomi.domain.download.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class DownloadSlotAllocatorTest {

    private fun source(prefix: String, count: Int, cap: Int) =
        List(count) { "$prefix$it" } to cap

    @Test
    @DisplayName("Fills every configured slot from a single capped source")
    fun singleSourceUsesAllSlots() {
        // The regression: one metered source plus "Download slots: 5" only ever ran two
        // downloads, because the per-source politeness cap acted as a hard ceiling.
        val active = allocateDownloadSlots(listOf(source("a", count = 8, cap = 2)), slots = 5)

        active shouldContainExactly listOf("a0", "a1", "a2", "a3", "a4")
    }

    @Test
    @DisplayName("Never hands out more downloads than the queue holds")
    fun neverExceedsQueueSize() {
        val active = allocateDownloadSlots(listOf(source("a", count = 3, cap = 2)), slots = 5)

        active shouldContainExactly listOf("a0", "a1", "a2")
    }

    @Test
    @DisplayName("Spreads across sources before going past any source's cap")
    fun spreadsAcrossSourcesFirst() {
        val active = allocateDownloadSlots(
            listOf(
                source("a", count = 8, cap = 2),
                source("b", count = 8, cap = 2),
                source("c", count = 8, cap = 2),
            ),
            slots = 5,
        )

        // One from each source, then a second from each, stopping at the slot count. No
        // source exceeds its cap of 2, because the others can still use the free slots.
        active shouldContainExactly listOf("a0", "b0", "c0", "a1", "b1")
    }

    @Test
    @DisplayName("Only exceeds a cap once the other sources have nothing left")
    fun topsUpOnlyWithLeftoverSlots() {
        val active = allocateDownloadSlots(
            listOf(
                source("a", count = 8, cap = 2),
                source("b", count = 1, cap = 2),
            ),
            slots = 5,
        )

        // b contributes its single download; the slots it cannot use go to a.
        active shouldContainExactly listOf("a0", "b0", "a1", "a2", "a3")
    }

    @Test
    @DisplayName("Honours an uncapped (unmetered) source")
    fun uncappedSource() {
        val active = allocateDownloadSlots(listOf(source("a", count = 8, cap = 8)), slots = 5)

        active shouldContainExactly listOf("a0", "a1", "a2", "a3", "a4")
    }

    @Test
    @DisplayName("Returns nothing for an empty queue or a non-positive slot count")
    fun degenerateInputs() {
        allocateDownloadSlots(emptyList<Pair<List<String>, Int>>(), slots = 5) shouldBe emptyList()
        allocateDownloadSlots(listOf(source("a", count = 8, cap = 2)), slots = 0) shouldBe emptyList()
        allocateDownloadSlots(listOf(source("a", count = 0, cap = 2)), slots = 5) shouldBe emptyList()
    }

    @Test
    @DisplayName("Keeps each source's queue order")
    fun preservesQueueOrder() {
        val active = allocateDownloadSlots(listOf(source("a", count = 8, cap = 2)), slots = 8)

        active shouldBe List(8) { "a$it" }
    }
}
