package tachiyomi.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Generates the baseline and startup profiles, see `:app:generateBaselineProfile`.
 *
 * The device has no extensions or library entries, so the profile covers app startup, navigation
 * and the settings and browse screens, but not the populated library, cover loading, the reader or
 * the player. The onboarding flow is skipped in the build types this runs against.
 */
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /** Only what runs up to the first frame, which is what R8 lays out first in the dex files. */
    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = targetPackageName,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun navigation() = baselineProfileRule.collect(
        packageName = targetPackageName,
    ) {
        pressHome()
        startActivityAndWait()

        listOf("Manga", "Manhwa", "Updates", "Browse", "Anime").forEach { clickText(it) }

        clickText("Browse")
        clickText("Extensions")
        clickText("Sources")

        clickText("More")
        clickText("Settings")
        device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
        device.waitForIdle()
    }
}

/** Doesn't fail when a label isn't there, so a changed layout only makes the profile smaller. */
internal fun MacrobenchmarkScope.clickText(text: String) {
    device.wait(Until.findObject(By.text(text)), UI_TIMEOUT_MILLIS)?.click()
    device.waitForIdle()
}

internal val targetPackageName: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId not passed as instrumentation runner argument")

private const val UI_TIMEOUT_MILLIS = 5_000L
