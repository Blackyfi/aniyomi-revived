package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Clears the stored global-update item restrictions once, so existing installs
 * pick up the new "no restrictions" default instead of keeping the old set.
 *
 * The previous default enabled all four at once, which made the global update
 * silently skip most of the library. ENTRY_HAS_UNVIEWED is the worst of them:
 * it skips any entry that has an unread chapter, so a single unread chapter
 * stops the entry being polled and it can never become read-up-to-date again.
 * ENTRY_NON_VIEWED does the same for anything never opened, and
 * ENTRY_OUTSIDE_RELEASE_PERIOD strands entries whose source cannot supply real
 * chapter upload dates, because the cadence estimator then falls back to a
 * 7-day interval that doubles up to MAX_INTERVAL (28) days.
 *
 * Anyone who actually wants these can re-tick them under
 * Settings -> Library -> Global update -> Skip updating entries.
 */
class ResetUpdateRestrictionsMigration : Migration {
    override val version = 159f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val libraryPreferences = migrationContext.get<LibraryPreferences>() ?: return false
        libraryPreferences.autoUpdateItemRestrictions().delete()
        return true
    }
}
