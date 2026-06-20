package mihon.core.migration.migrations

import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.extensionrepo.exception.SaveExtensionRepoException
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Seed the default Aniyomi-Revived manga extension repository so a fresh install
 * already has it configured (no manual "add repo" step).
 *
 * Runs as an [Migration.ALWAYS] migration: on a fresh install the migrator runs
 * only ALWAYS migrations (InitialMigrationStrategy), and on upgrades ALWAYS
 * migrations re-run too. [MangaExtensionRepoRepository.upsertRepo] is keyed on
 * baseUrl, so re-running is an idempotent no-op. The fingerprint is the repo's
 * real signing-cert SHA-256 (matches its published repo.json), so APKs from it
 * are trusted immediately — no "untrusted extension" prompt.
 *
 * Manga-only: the repo currently publishes manga extensions. If anime
 * extensions are added later, seed AnimeExtensionRepoRepository the same way.
 */
class SetupDefaultExtensionRepoMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val mangaRepo = migrationContext.get<MangaExtensionRepoRepository>()
            ?: return@withIOContext false

        try {
            mangaRepo.upsertRepo(
                baseUrl = REPO_BASE_URL,
                name = REPO_NAME,
                shortName = REPO_SHORT_NAME,
                website = REPO_WEBSITE,
                signingKeyFingerprint = REPO_FINGERPRINT,
            )
        } catch (e: SaveExtensionRepoException) {
            // Already present, or its fingerprint is trusted under another repo —
            // fine for a seed; don't fail the migration chain.
            logcat(LogPriority.INFO, e) { "Default extension repo already configured" }
        }

        return@withIOContext true
    }

    private companion object {
        const val REPO_BASE_URL =
            "https://raw.githubusercontent.com/Blackyfi/aniyomi-revived-extensions/repo"
        const val REPO_NAME = "Aniyomi Revived Extensions"
        const val REPO_SHORT_NAME = "arext"
        const val REPO_WEBSITE = "https://github.com/Blackyfi/aniyomi-revived-extensions"

        // SHA-256 of the repo's APK signing cert; must match its published repo.json.
        const val REPO_FINGERPRINT =
            "e4bbc0829bf2b1ef674b4772407c93898253620c5bebca3b3ddb372b6863ca9b"
    }
}
