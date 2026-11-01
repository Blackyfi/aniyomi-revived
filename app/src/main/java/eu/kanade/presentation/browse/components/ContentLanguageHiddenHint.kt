package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Keeps installed sources that the content language leaves out within reach. Only shown once that
 * setting is in use, so nothing changes for anyone who only has per-language toggles off.
 */
@Composable
fun ContentLanguageHiddenHint(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Text(
                text = pluralStringResource(AYMR.plurals.content_language_hidden_sources, count, count),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(text = stringResource(AYMR.strings.content_language_hidden_action))
            }
        }
    }
}
