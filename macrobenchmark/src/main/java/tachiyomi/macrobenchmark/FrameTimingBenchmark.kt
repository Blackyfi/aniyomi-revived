package tachiyomi.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for the scrolling in the app that doesn't depend on what's installed. A clean install
 * has nothing to put in the library or browse grids, so restore a backup into the benchmark build and
 * add a library case here to measure those.
 */
@RunWith(AndroidJUnit4ClassRunner::class)
class FrameTimingBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun settingsFling() = benchmarkRule.measureRepeated(
        packageName = targetPackageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            clickText("More")
            clickText("Settings")
        },
    ) {
        val list = device.findObject(By.scrollable(true)) ?: error("Nothing to scroll")
        list.setGestureMargin(device.displayWidth / 10)
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}
