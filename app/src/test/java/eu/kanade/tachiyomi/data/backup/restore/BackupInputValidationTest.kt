package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.models.isValid
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BackupInputValidationTest {

    private fun repo(baseUrl: String, fingerprint: String = "abc123") = BackupExtensionRepos(
        baseUrl = baseUrl,
        name = "Repo",
        shortName = null,
        website = "https://example.org",
        signingKeyFingerprint = fingerprint,
    )

    @Test
    fun `source preference files are the only restorable ones`() {
        PreferenceRestorer.isSourcePreferenceFile("source_2499283573021220255") shouldBe true
        PreferenceRestorer.isSourcePreferenceFile("source_-42") shouldBe true

        PreferenceRestorer.isSourcePreferenceFile("com.example.app_preferences") shouldBe false
        PreferenceRestorer.isSourcePreferenceFile("source_") shouldBe false
        PreferenceRestorer.isSourcePreferenceFile("source_1/../../x") shouldBe false
        PreferenceRestorer.isSourcePreferenceFile("") shouldBe false
    }

    @Test
    fun `extension repos must be https and pinned to a fingerprint`() {
        repo("https://example.org/repo").isValid() shouldBe true
        // Written by the fingerprint migration for repos added before fingerprints existed.
        repo("https://example.org/repo", "NOFINGERPRINT-1").isValid() shouldBe true

        repo("http://example.org/repo").isValid() shouldBe false
        repo("file:///sdcard/repo").isValid() shouldBe false
        repo("not a url").isValid() shouldBe false
        repo("https://example.org/repo", " ").isValid() shouldBe false
    }
}
