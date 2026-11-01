package eu.kanade.presentation.browse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun InLibraryBadge(enabled: Boolean) {
    if (enabled) {
        val description = stringResource(MR.strings.in_library)
        Badge(
            imageVector = Icons.Outlined.CollectionsBookmark,
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        )
    }
}
