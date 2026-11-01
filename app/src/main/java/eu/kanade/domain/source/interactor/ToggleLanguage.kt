package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences

class ToggleLanguage(
    val preferences: SourcePreferences,
) {

    private val setContentLanguage = SetContentLanguage(preferences)

    fun await(language: String) {
        setContentLanguage.onManualToggle(language)
    }
}
