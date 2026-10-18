package eu.kanade.tachiyomi.ui.reader.loader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

class TorrentRetryPolicyTest {

    @Test
    fun `timeouts keep retrying for a while without peers`() {
        val policy = TorrentRetryPolicy(startedAtMs = 0)
        policy.shouldRetry(SocketTimeoutException(), nowMs = 30_000) shouldBe true
        policy.shouldRetry(SocketTimeoutException(), nowMs = 60_000) shouldBe true
        policy.shouldRetry(SocketTimeoutException(), nowMs = 90_000) shouldBe false
    }

    @Test
    fun `seeing a peer extends the wait`() {
        val policy = TorrentRetryPolicy(startedAtMs = 0)
        policy.sawPeers = true
        policy.shouldRetry(SocketTimeoutException(), nowMs = 90_000) shouldBe true
        policy.shouldRetry(SocketTimeoutException(), nowMs = 300_000) shouldBe false
    }

    @Test
    fun `a timeout wrapped by Call await counts as a timeout`() {
        val policy = TorrentRetryPolicy(startedAtMs = 0)
        repeat(5) {
            policy.shouldRetry(IOException("timeout", SocketTimeoutException()), nowMs = 1_000) shouldBe true
        }
    }

    @Test
    fun `only a long wait without peers looks dead`() {
        val policy = TorrentRetryPolicy(startedAtMs = 0)
        policy.looksDead(nowMs = 1_000) shouldBe false
        policy.looksDead(nowMs = 90_000) shouldBe true
        policy.sawPeers = true
        policy.looksDead(nowMs = 90_000) shouldBe false
    }

    @Test
    fun `other failures only get a couple of retries`() {
        val policy = TorrentRetryPolicy(startedAtMs = 0)
        policy.shouldRetry(IOException("refused"), nowMs = 0) shouldBe true
        policy.shouldRetry(IOException("refused"), nowMs = 0) shouldBe true
        policy.shouldRetry(IOException("refused"), nowMs = 0) shouldBe false
    }
}
