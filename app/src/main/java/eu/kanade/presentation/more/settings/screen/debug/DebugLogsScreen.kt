package eu.kanade.presentation.more.settings.screen.debug

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import logcat.LogPriority
import tachiyomi.core.common.util.system.InMemoryLogcatBuffer
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugLogsScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { DebugLogsScreenModel() }

        val filtered = remember(model.entries, model.query, model.minPriority) {
            model.entries.filter { entry ->
                entry.priority.priorityInt >= model.minPriority.priorityInt &&
                    (
                        model.query.isBlank() ||
                            entry.message.contains(model.query, ignoreCase = true) ||
                            entry.tag.contains(model.query, ignoreCase = true)
                        )
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_debug_logs),
                    subtitle = "${filtered.size} / ${model.entries.size}",
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_copy_to_clipboard),
                                    icon = Icons.Filled.ContentCopy,
                                    onClick = {
                                        if (filtered.isEmpty()) {
                                            context.toast(MR.strings.info_debug_logs_empty)
                                        } else {
                                            context.copyToClipboard(
                                                "Aniyomi debug logs",
                                                buildLogText(context, filtered),
                                            )
                                        }
                                    },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_refresh),
                                    icon = Icons.Filled.Refresh,
                                    onClick = model::refresh,
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_share),
                                    onClick = {
                                        if (filtered.isEmpty()) {
                                            context.toast(MR.strings.info_debug_logs_empty)
                                        } else {
                                            shareLogs(context, filtered)
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
            Column(modifier = Modifier.fillMaxSize()) {
                FilterRow(
                    query = model.query,
                    onQueryChange = { model.query = it },
                    minPriority = model.minPriority,
                    onPriorityChange = { model.minPriority = it },
                    modifier = Modifier.padding(
                        top = contentPadding.calculateTopPadding(),
                    ),
                )

                if (filtered.isEmpty()) {
                    EmptyState()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding(),
                        ) + PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        // Newest entries first so the latest events are visible immediately.
                        items(filtered.asReversed()) { entry ->
                            LogRow(entry)
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FilterRow(
        query: String,
        onQueryChange: (String) -> Unit,
        minPriority: LogPriority,
        onPriorityChange: (LogPriority) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(MR.strings.action_search)) },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRIORITY_FILTERS.forEach { (label, priority) ->
                    FilterChip(
                        selected = minPriority == priority,
                        onClick = { onPriorityChange(priority) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
    }

    @Composable
    private fun LogRow(entry: InMemoryLogcatBuffer.Entry) {
        val color = priorityColor(entry.priority)
        SelectionContainer {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    text = "${TIME_FORMAT.format(Date(entry.timeMillis))}  " +
                        "${priorityChar(entry.priority)}/${entry.tag}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(MR.strings.info_debug_logs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun priorityColor(priority: LogPriority): Color = when (priority) {
        LogPriority.ERROR, LogPriority.ASSERT -> MaterialTheme.colorScheme.error
        LogPriority.WARN -> MaterialTheme.colorScheme.tertiary
        LogPriority.INFO -> MaterialTheme.colorScheme.primary
        LogPriority.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        LogPriority.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }

    private fun shareLogs(context: Context, entries: List<InMemoryLogcatBuffer.Entry>) {
        try {
            val file = context.createFileInCacheDir("aniyomi_debug_logs.txt")
            file.writeText(buildLogText(context, entries))
            val uri = file.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        } catch (e: Throwable) {
            context.toast("Failed to share logs")
        }
    }

    private fun buildLogText(context: Context, entries: List<InMemoryLogcatBuffer.Entry>): String {
        val header = CrashLogUtil(context).getDebugInfo()
        // Export oldest-first (chronological), which reads naturally as a trace.
        val body = entries.joinToString("\n") { entry ->
            "${FULL_FORMAT.format(Date(entry.timeMillis))} " +
                "${priorityChar(entry.priority)}/${entry.tag}: ${entry.message}"
        }
        return "$header\n\n--- Debug logs (${entries.size}) ---\n$body\n"
    }

    private fun priorityChar(priority: LogPriority): Char = when (priority) {
        LogPriority.VERBOSE -> 'V'
        LogPriority.DEBUG -> 'D'
        LogPriority.INFO -> 'I'
        LogPriority.WARN -> 'W'
        LogPriority.ERROR -> 'E'
        LogPriority.ASSERT -> 'A'
    }
}

private class DebugLogsScreenModel : ScreenModel {
    var entries by mutableStateOf(InMemoryLogcatBuffer.snapshot())
        private set
    var query by mutableStateOf("")
    var minPriority by mutableStateOf(LogPriority.VERBOSE)

    fun refresh() {
        entries = InMemoryLogcatBuffer.snapshot()
    }

    fun clear() {
        InMemoryLogcatBuffer.clear()
        refresh()
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
private val FULL_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

private val PRIORITY_FILTERS = listOf(
    "All" to LogPriority.VERBOSE,
    "Debug" to LogPriority.DEBUG,
    "Info" to LogPriority.INFO,
    "Warn" to LogPriority.WARN,
    "Error" to LogPriority.ERROR,
)
