package eu.kanade.tachiyomi.ui.deeplink

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeepLinkScreenTypeTest {

    @Test
    fun `known values parse`() {
        DeepLinkScreenType.fromExtra("ANIME") shouldBe DeepLinkScreenType.ANIME
        DeepLinkScreenType.fromExtra("MANGA") shouldBe DeepLinkScreenType.MANGA
    }

    @Test
    fun `missing or unknown values from other apps fall back instead of throwing`() {
        DeepLinkScreenType.fromExtra(null) shouldBe DeepLinkScreenType.ANIME
        DeepLinkScreenType.fromExtra("") shouldBe DeepLinkScreenType.ANIME
        DeepLinkScreenType.fromExtra("manga") shouldBe DeepLinkScreenType.ANIME
        DeepLinkScreenType.fromExtra("NOT_A_TYPE") shouldBe DeepLinkScreenType.ANIME
    }
}
