package eu.kanade.tachiyomi.ui.library.manga

import tachiyomi.domain.entries.manga.model.MangaType

/**
 * Screen model for the Manhwa library tab. It is a distinct type from [MangaLibraryScreenModel] only
 * so Voyager stores a separate instance per tab (this Voyager version's `rememberScreenModel` has no
 * tag parameter, and both tabs live under the same navigator). Behaviour is identical apart from the
 * [MangaType] used to split entries between the two tabs.
 */
class ManhwaLibraryScreenModel : MangaLibraryScreenModel(libraryType = MangaType.MANHWA)
