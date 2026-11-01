package eu.kanade.presentation.library.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.FolderOff
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.screens.EmptyScreenAction

/**
 * Ways out of an empty library page when the user's own filters are what emptied it.
 */
internal fun libraryEmptyActions(
    hasActiveFilters: Boolean,
    downloadedOnly: Boolean,
    onResetFilters: () -> Unit,
    onShowAllEntries: () -> Unit,
): ImmutableList<EmptyScreenAction> {
    return buildList {
        if (hasActiveFilters) {
            add(
                EmptyScreenAction(
                    stringRes = AYMR.strings.action_reset_filters,
                    icon = Icons.Outlined.FilterAltOff,
                    onClick = onResetFilters,
                ),
            )
        }
        if (downloadedOnly) {
            add(
                EmptyScreenAction(
                    stringRes = AYMR.strings.action_show_all_entries,
                    icon = Icons.Outlined.FolderOff,
                    onClick = onShowAllEntries,
                ),
            )
        }
    }.toImmutableList()
}
