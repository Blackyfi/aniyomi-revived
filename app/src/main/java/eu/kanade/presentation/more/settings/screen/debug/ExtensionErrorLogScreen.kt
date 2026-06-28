package eu.kanade.presentation.more.settings.screen.debug

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.ExtensionErrorEntry
import eu.kanade.tachiyomi.util.ExtensionErrorStorage
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Advanced → "Extension error log". Lists the persisted extension failures captured by
 * [ExtensionErrorStorage], with per-item copy/delete and toolbar copy-all/clear-all.
 */
class ExtensionErrorLogScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { ExtensionErrorLogScreenModel() }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Extension errors",
                    subtitle = "${model.entries.size}",
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_copy_to_clipboard),
                                    icon = Icons.Filled.ContentCopy,
                                    onClick = {
                                        if (model.entries.isEmpty()) {
                                            context.toast(EMPTY_MESSAGE)
                                        } else {
                                            context.copyToClipboard(
                                                "Extension errors",
                                                buildAllText(context, model.entries),
                                            )
                                        }
                                    },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_clear),
                                    onClick = model::clear,
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (model.entries.isEmpty()) {
                EmptyState(contentPadding)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding +
                        PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Newest first.
                    items(model.entries.asReversed(), key = { it.id }) { entry ->
                        ErrorCard(
                            entry = entry,
                            onCopy = {
                                context.copyToClipboard(
                                    "Extension error",
                                    buildEntryText(entry),
                                )
                            },
                            onDelete = { model.delete(entry.id) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ErrorCard(
        entry: ExtensionErrorEntry,
        onCopy: () -> Unit,
        onDelete: () -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${entry.sourceName} • ${entry.operation}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (entry.detail.isNotBlank()) {
                            Text(
                                text = entry.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = FULL_FORMAT.format(Date(entry.timeMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                        )
                    }
                }

                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (expanded) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SelectionContainer {
                        Text(
                            text = entry.stackTrace,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                } else {
                    Text(
                        text = "Tap to show full stack trace",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun EmptyState(contentPadding: PaddingValues) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = EMPTY_MESSAGE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    private fun buildEntryText(entry: ExtensionErrorEntry): String = buildString {
        appendLine("${FULL_FORMAT.format(Date(entry.timeMillis))}  [${entry.sourceName} #${entry.sourceId}]")
        appendLine("Operation: ${entry.operation}")
        if (entry.detail.isNotBlank()) appendLine("Detail: ${entry.detail}")
        appendLine("Error: ${entry.message}")
        appendLine()
        append(entry.stackTrace)
    }

    private fun buildAllText(context: Context, entries: List<ExtensionErrorEntry>): String {
        val header = CrashLogUtil(context).getDebugInfo()
        val body = entries.joinToString("\n\n------------------------------\n\n") { buildEntryText(it) }
        return "$header\n\n--- Extension errors (${entries.size}) ---\n\n$body\n"
    }
}

private class ExtensionErrorLogScreenModel : ScreenModel {
    var entries by mutableStateOf(ExtensionErrorStorage.getAll())
        private set

    fun delete(id: String) {
        ExtensionErrorStorage.delete(id)
        entries = ExtensionErrorStorage.getAll()
    }

    fun clear() {
        ExtensionErrorStorage.clear()
        entries = ExtensionErrorStorage.getAll()
    }
}

private const val EMPTY_MESSAGE = "No extension errors recorded"
private val FULL_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
