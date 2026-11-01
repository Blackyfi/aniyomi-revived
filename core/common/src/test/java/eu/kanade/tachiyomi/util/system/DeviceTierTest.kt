package eu.kanade.tachiyomi.util.system

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeviceTierTest {

    private val gib = 1024L * 1024 * 1024

    @Test
    fun `under 3 GiB is low`() {
        DeviceTier.fromTotalMemory(0) shouldBe DeviceTier.LOW
        DeviceTier.fromTotalMemory(3 * gib - 1) shouldBe DeviceTier.LOW
    }

    @Test
    fun `3 GiB up to 5 GiB is average`() {
        DeviceTier.fromTotalMemory(3 * gib) shouldBe DeviceTier.AVERAGE
        DeviceTier.fromTotalMemory(5 * gib - 1) shouldBe DeviceTier.AVERAGE
    }

    @Test
    fun `5 GiB and above is high`() {
        DeviceTier.fromTotalMemory(5 * gib) shouldBe DeviceTier.HIGH
        DeviceTier.fromTotalMemory(12 * gib) shouldBe DeviceTier.HIGH
    }
}
