package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.PreviewLightDark
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun DownloadsBadge(count: Long) {
    if (count > 0) {
        val context = LocalContext.current
        Badge(
            text = "$count",
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = context.pluralStringResource(
                    MR.plurals.badge_downloaded_count,
                    count.toInt(),
                    count,
                )
            },
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@Composable
internal fun UnviewedBadge(count: Long) {
    if (count > 0) {
        val context = LocalContext.current
        Badge(
            text = "$count",
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = context.pluralStringResource(
                    MR.plurals.badge_unviewed_count,
                    count.toInt(),
                    count,
                )
            },
        )
    }
}

@Composable
internal fun LanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
) {
    if (isLocal) {
        val description = stringResource(MR.strings.label_local)
        Badge(
            imageVector = Icons.Outlined.Folder,
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    } else if (sourceLanguage.isNotEmpty()) {
        Badge(
            text = sourceLanguage.uppercase(),
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@PreviewLightDark
@Composable
private fun BadgePreview() {
    TachiyomiPreviewTheme {
        Column {
            DownloadsBadge(count = 10)
            UnviewedBadge(count = 10)
            LanguageBadge(isLocal = true, sourceLanguage = "EN")
            LanguageBadge(isLocal = false, sourceLanguage = "EN")
        }
    }
}
