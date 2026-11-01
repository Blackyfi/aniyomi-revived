package eu.kanade.tachiyomi.ui.reader.loader

import java.io.IOException
import java.io.InterruptedIOException

/**
 * Decides whether a failed torrent page download is worth another attempt.
 *
 * The server only answers once it has bytes, and that can take a while: peers have to be found
 * first, and the client's read timeout is shorter than that wait. The request timing out says little
 * about the torrent, so what decides is whether any peer has shown up.
 */
internal class TorrentRetryPolicy(private val startedAtMs: Long) {

    /** Set once the server reports a connected peer. */
    @Volatile
    var sawPeers = false

    private var otherFailures = 0

    /** Whether a failure at [nowMs] means nobody's sharing this, rather than something going wrong. */
    fun looksDead(nowMs: Long): Boolean = !sawPeers && nowMs - startedAtMs >= MIN_WAIT_FOR_DEAD_MS

    fun shouldRetry(error: IOException, nowMs: Long): Boolean {
        if (error.isTimeout()) {
            return nowMs - startedAtMs < if (sawPeers) WAIT_WITH_PEERS_MS else WAIT_WITHOUT_PEERS_MS
        }
        // Anything else (server restarting, connection dropped) either clears quickly or won't.
        return ++otherFailures <= MAX_OTHER_FAILURES
    }

    // Call.await() wraps the original exception in a plain IOException.
    private fun IOException.isTimeout() = this is InterruptedIOException || cause is InterruptedIOException

    private companion object {
        const val WAIT_WITHOUT_PEERS_MS = 75_000L
        const val WAIT_WITH_PEERS_MS = 300_000L
        const val MIN_WAIT_FOR_DEAD_MS = 30_000L
        const val MAX_OTHER_FAILURES = 2
    }
}
