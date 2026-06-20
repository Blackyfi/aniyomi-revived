package eu.kanade.tachiyomi.ui.library.manga

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import kotlinx.coroutines.channels.Channel
import tachiyomi.domain.entries.manga.model.MangaType
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A sibling of [MangaLibraryTab] that shows only entries classified as manhwa. Both tabs share the
 * same library screen (see [MangaLibraryTabContent]); the only difference is the [MangaType] used to
 * split the favorites between them.
 */
data object ManhwaLibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val title = stringResource(AYMR.strings.label_manhwa_library)
            return TabOptions(
                index = 6u,
                title = title,
                icon = rememberVectorPainter(Icons.Outlined.ViewDay),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        MangaLibraryTabContent(
            libraryType = MangaType.MANHWA,
            screenModelTag = "manhwa-library",
            defaultTitle = stringResource(AYMR.strings.label_manhwa_library),
            fromMore = false,
            queryEvent = queryEvent,
            requestSettingsSheetEvent = requestSettingsSheetEvent,
        )
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet
    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
