package eu.kanade.tachiyomi.ui.deeplink

enum class DeepLinkScreenType {
    ANIME,
    MANGA,
    ;

    companion object {
        /**
         * Parses the search type extra of an incoming intent. MainActivity is exported, so the value
         * can come from any app: anything unknown falls back to [ANIME] rather than throwing.
         */
        fun fromExtra(value: String?): DeepLinkScreenType {
            return entries.firstOrNull { it.name == value } ?: ANIME
        }
    }
}
