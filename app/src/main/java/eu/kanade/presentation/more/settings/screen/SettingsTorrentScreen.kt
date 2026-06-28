package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.torrentServer.TorrentServerPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.OutlinedNumericChooser
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

object SettingsTorrentScreen : SearchableSettings {

    /**
     * Sub-directory created by the native torrent server underneath the path passed to
     * `startTorrentServer(filesDir.absolutePath)`. Used as the default cache location to clear.
     */
    private const val TORRENT_DATA_DIR = "torrserver"

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AYMR.strings.pref_category_torrent

    @Composable
    override fun getPreferences(): List<Preference> {
        val torrentServerPreferences = remember { Injekt.get<TorrentServerPreferences>() }
        return listOf(
            getServerGroup(torrentServerPreferences),
            getStorageGroup(torrentServerPreferences),
            Preference.PreferenceItem.SwitchPreference(
                preference = torrentServerPreferences.enableSeeding(),
                title = stringResource(AYMR.strings.pref_torrent_enable_seeding),
                subtitle = stringResource(AYMR.strings.pref_torrent_enable_seeding_summary),
            ),
            Preference.PreferenceItem.InfoPreference(
                stringResource(AYMR.strings.pref_torrent_server_info),
            ),
        )
    }

    @Composable
    private fun getServerGroup(
        torrentServerPreferences: TorrentServerPreferences,
    ): Preference.PreferenceGroup {
        val portPref = torrentServerPreferences.port()
        val port by portPref.collectAsState()
        var showPortDialog by rememberSaveable { mutableStateOf(false) }
        if (showPortDialog) {
            ServerPortDialog(
                initialValue = port,
                onDismissRequest = { showPortDialog = false },
                onConfirm = {
                    portPref.set(it)
                    showPortDialog = false
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(AYMR.strings.pref_category_torrent_server),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(AYMR.strings.pref_torrent_port),
                    subtitle = port.toString(),
                    onClick = { showPortDialog = true },
                ),
                Preference.PreferenceItem.MultiLineEditTextPreference(
                    preference = torrentServerPreferences.trackers(),
                    title = stringResource(AYMR.strings.pref_torrent_trackers),
                    subtitle = stringResource(AYMR.strings.pref_torrent_trackers_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getStorageGroup(
        torrentServerPreferences: TorrentServerPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val downloadDir by torrentServerPreferences.downloadDir().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(AYMR.strings.pref_category_torrent_storage),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = torrentServerPreferences.cacheSize(),
                    entries = listOf(64L, 96L, 128L, 256L, 512L, 1024L, 2048L)
                        .associateWith { "$it MiB" }
                        .toImmutableMap(),
                    title = stringResource(AYMR.strings.pref_torrent_cache_size),
                ),
                Preference.PreferenceItem.EditTextInfoPreference(
                    preference = torrentServerPreferences.downloadDir(),
                    dialogSubtitle = stringResource(AYMR.strings.pref_torrent_download_dir_summary),
                    title = stringResource(AYMR.strings.pref_torrent_download_dir),
                    subtitle = downloadDir.ifBlank {
                        stringResource(AYMR.strings.pref_torrent_download_dir_internal)
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(AYMR.strings.pref_torrent_clear_cache),
                    subtitle = stringResource(AYMR.strings.pref_torrent_clear_cache_summary),
                    onClick = {
                        scope.launch {
                            val cleared = withContext(Dispatchers.IO) {
                                // Stop the server first so files aren't held open while deleting.
                                if (TorrentServerService.isRunning) {
                                    TorrentServerService.stop(context)
                                }
                                val cacheDir = if (downloadDir.isNotBlank()) {
                                    File(downloadDir)
                                } else {
                                    File(context.filesDir, TORRENT_DATA_DIR)
                                }
                                logcat(LogPriority.INFO) { "[Torrent] clearing cache dir: ${cacheDir.absolutePath}" }
                                // Guard: never wipe the app's internal root, only a sub-directory.
                                if (cacheDir != context.filesDir && cacheDir.exists()) {
                                    cacheDir.deleteRecursively()
                                } else {
                                    !cacheDir.exists()
                                }
                            }
                            context.toast(
                                if (cleared) {
                                    AYMR.strings.torrent_cache_cleared
                                } else {
                                    AYMR.strings.torrent_cache_clear_error
                                },
                            )
                        }
                    },
                ),
            ),
        )
    }

    @Composable
    private fun ServerPortDialog(
        initialValue: Int,
        onDismissRequest: () -> Unit,
        onConfirm: (Int) -> Unit,
    ) {
        var currentValue by remember { mutableIntStateOf(initialValue) }
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(AYMR.strings.pref_torrent_port)) },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .padding(bottom = MaterialTheme.padding.medium)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        OutlinedNumericChooser(
                            label = stringResource(AYMR.strings.pref_torrent_port),
                            placeholder = "8090",
                            suffix = "",
                            value = currentValue,
                            step = 1,
                            min = 1,
                            onValueChanged = { currentValue = it },
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(currentValue) }) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
        )
    }
}
