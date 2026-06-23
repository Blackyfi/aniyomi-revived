package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.ensureActive
import okio.Buffer
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.coroutineContext

/**
 * Measures the available download throughput and recommends a concurrent download-slot count.
 *
 * The test streams a fixed amount of data from a clean, analytics-free static download endpoint
 * (see [DownloadPreferences.downloadSpeedTestUrl]), measures the elapsed time and maps the
 * resulting throughput to a slot count clamped to the 1..5 range used by the downloaders.
 */
class DownloadSpeedTester(
    private val network: NetworkHelper = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
) {

    /**
     * @param mbps measured throughput in megabytes per second (MB/s).
     * @param recommendedSlots recommended concurrent download-slot count, clamped to 1..5.
     */
    data class Result(
        val mbps: Double,
        val recommendedSlots: Int,
    )

    /**
     * Runs the speed test, streaming up to [SAMPLE_BYTES] from [url] into a throwaway buffer.
     *
     * @param url the endpoint to download from, defaults to the configured test URL.
     */
    suspend fun test(url: String = downloadPreferences.downloadSpeedTestUrl().get()): Result = withIOContext {
        val response = network.client.newCall(GET(url)).awaitSuccess()
        try {
            val source = response.body.source()
            val sink = Buffer()
            var totalRead = 0L
            val startNanos = System.nanoTime()
            while (totalRead < SAMPLE_BYTES) {
                // Honor cancellation between reads.
                coroutineContext.ensureActive()
                val read = source.read(sink, READ_CHUNK_BYTES)
                if (read == -1L) break
                totalRead += read
                // Discard the data; we only care about throughput.
                sink.clear()
            }
            val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
            val mbps = if (elapsedSeconds > 0) {
                (totalRead.toDouble() / BYTES_PER_MB) / elapsedSeconds
            } else {
                0.0
            }
            Result(mbps = mbps, recommendedSlots = recommendSlots(mbps))
        } finally {
            response.close()
        }
    }

    /**
     * Maps a measured throughput (MB/s) to a recommended slot count, clamped to 1..5.
     */
    private fun recommendSlots(mbps: Double): Int = when {
        mbps < 1 -> 1 // < 1 MB/s
        mbps < 3 -> 2 // 1-3 MB/s
        mbps < 8 -> 3 // 3-8 MB/s
        mbps < 20 -> 4 // 8-20 MB/s
        else -> 5 // >= 20 MB/s
    }

    companion object {
        private const val BYTES_PER_MB = 1024.0 * 1024.0

        // Amount of data to stream before computing throughput: ~8 MiB.
        private const val SAMPLE_BYTES = 8L * 1024 * 1024

        // Per-read chunk size: 8 KiB.
        private const val READ_CHUNK_BYTES = 8L * 1024
    }
}
