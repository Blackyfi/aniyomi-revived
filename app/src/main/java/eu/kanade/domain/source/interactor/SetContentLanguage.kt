package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.ContentLanguages
import eu.kanade.domain.source.service.SourcePreferences

class SetContentLanguage(
    private val preferences: SourcePreferences,
) {

    fun toggle(base: String, known: Set<String>) {
        update { ContentLanguages.toggle(it, base, known) }
    }

    fun selectAll(known: Set<String>) {
        update { ContentLanguages.selectAll(it, known) }
    }

    fun reconcile(known: Set<String>) {
        update { ContentLanguages.reconcile(it, known) }
    }

    fun onManualToggle(language: String) {
        update { ContentLanguages.manualToggle(it, language) }
    }

    /** Only writes what changed, so that a preference nobody chose never becomes set. */
    private fun update(transform: (ContentLanguages.State) -> ContentLanguages.State) {
        synchronized(LOCK) {
            val enabledPref = preferences.enabledLanguages()
            val selectionPref = preferences.contentLanguageSelection()
            val state = ContentLanguages.State(enabledPref.get(), selectionPref.get())
            val updated = transform(state)
            if (updated.enabled != state.enabled) enabledPref.set(updated.enabled)
            if (updated.selection != state.selection) selectionPref.set(updated.selection)
        }
    }

    private companion object {
        val LOCK = Any()
    }
}
