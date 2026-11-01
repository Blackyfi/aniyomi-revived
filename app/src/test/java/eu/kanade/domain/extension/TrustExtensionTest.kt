package eu.kanade.domain.extension

import android.content.pm.PackageInfo
import eu.kanade.domain.extension.anime.interactor.TrustAnimeExtension
import eu.kanade.domain.extension.manga.interactor.TrustMangaExtension
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.extensionrepo.anime.repository.AnimeExtensionRepoRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Test

/** Which extensions get loaded into the app's process is decided here. */
class TrustExtensionTest {

    private val pkgInfo = mockk<PackageInfo>()

    private fun repo(fingerprint: String) = ExtensionRepo(
        baseUrl = "https://example.org/$fingerprint",
        name = "Repo",
        shortName = null,
        website = "https://example.org",
        signingKeyFingerprint = fingerprint,
    )

    private fun anime(vararg repoFingerprints: String): TrustAnimeExtension {
        val repos = mockk<AnimeExtensionRepoRepository>()
        coEvery { repos.getAll() } returns repoFingerprints.map(::repo)
        return TrustAnimeExtension(repos, mockk())
    }

    private fun manga(vararg repoFingerprints: String): TrustMangaExtension {
        val repos = mockk<MangaExtensionRepoRepository>()
        coEvery { repos.getAll() } returns repoFingerprints.map(::repo)
        return TrustMangaExtension(repos, mockk())
    }

    @Test
    fun `only a fingerprint of a configured repo is trusted`() = runTest {
        anime("repo-key").isTrusted(pkgInfo, listOf("repo-key")) shouldBe true
        manga("repo-key").isTrusted(pkgInfo, listOf("repo-key")) shouldBe true

        anime("repo-key").isTrusted(pkgInfo, listOf("other-key")) shouldBe false
        manga("repo-key").isTrusted(pkgInfo, listOf("other-key")) shouldBe false
    }

    @Test
    fun `key rotation is trusted through the certificate history`() = runTest {
        anime("old-key").isTrusted(pkgInfo, listOf("old-key", "new-key")) shouldBe true
        manga("old-key").isTrusted(pkgInfo, listOf("old-key", "new-key")) shouldBe true
    }

    @Test
    fun `nothing is trusted without repos or signatures`() = runTest {
        anime().isTrusted(pkgInfo, listOf("any-key")) shouldBe false
        manga().isTrusted(pkgInfo, listOf("any-key")) shouldBe false

        anime("repo-key").isTrusted(pkgInfo, emptyList()) shouldBe false
        manga("repo-key").isTrusted(pkgInfo, emptyList()) shouldBe false
    }
}
