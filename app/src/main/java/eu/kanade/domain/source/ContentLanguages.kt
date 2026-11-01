package eu.kanade.domain.source

/**
 * The decisions behind the app-wide content language setting.
 *
 * The effective filter is always [State.enabled], the same per-language set the source and
 * extension filter screens toggle, so the two can never disagree. [State.selection] only records
 * which base languages the user picked here, so that a variant showing up later (a new `pt-PT`
 * source for someone who picked Portuguese) can follow that choice. It is empty until the setting
 * is first used, and everything here leaves the state alone while it is.
 */
object ContentLanguages {

    /** The lang of multi-language sources. */
    const val MULTI = "all"

    /** The lang of sources that aren't in a particular language, such as the local source. */
    const val OTHER = "other"

    /** Stored in place of base languages while "all languages" is chosen. */
    const val ALL_SELECTED = "*"

    /** Offered even before any extension repo is added, as during onboarding. */
    val COMMON = setOf(
        "ar", "bg", "bn", "ca", "cs", "da", "de", "el", "en", "es", "fa", "fi", "fil", "fr", "he",
        "hi", "hr", "hu", "id", "it", "ja", "ko", "lt", "ms", "my", "nl", "no", "pl", "pt", "ro",
        "ru", "sk", "sl", "sq", "sr", "sv", "ta", "th", "tr", "uk", "ur", "vi", "zh",
    )

    data class State(val enabled: Set<String>, val selection: Set<String>)

    fun baseOf(code: String): String = code.substringBefore('-').substringBefore('_').lowercase()

    /** The base languages among [codes], leaving out the ones that aren't really languages. */
    fun basesOf(codes: Collection<String>): Set<String> {
        return codes.filterNot { it.isBlank() || it == MULTI || it == OTHER }.map(::baseOf).toSet()
    }

    fun isAll(selection: Set<String>): Boolean = ALL_SELECTED in selection

    fun isChecked(state: State, base: String): Boolean {
        return !isAll(state.selection) && base in basesOf(state.enabled)
    }

    /** What the setting offers: what is really available, plus what is on, plus the usual ones. */
    fun options(enabled: Set<String>, known: Set<String>): Set<String> {
        return COMMON + basesOf(known) + basesOf(enabled)
    }

    /**
     * Ticks or unticks [base] and all its variants, leaving other languages exactly as they are:
     * someone who only enabled `pt-BR` by hand keeps that when they add French here.
     *
     * The last language can't be unticked, as that would leave nothing but multi-language sources.
     * Ticking a language while "all languages" is on narrows the choice to it.
     */
    fun toggle(state: State, base: String, known: Set<String>): State {
        if (isAll(state.selection)) {
            return State(enabled = setOf(MULTI, base) + variants(base, known), selection = setOf(base))
        }

        val selection = state.selection.ifEmpty { fullyEnabledBases(state.enabled, known) }
        // Multi-language sources stay visible when someone starts using the setting. Later on they
        // are left to the per-language toggle, which may have turned them off on purpose.
        val multi = if (state.selection.isEmpty()) setOf(MULTI) else emptySet()

        if (base !in basesOf(state.enabled)) {
            return State(
                enabled = state.enabled + multi + base + variants(base, known),
                selection = selection + base,
            )
        }
        if (basesOf(state.enabled).size == 1) return state

        return State(
            enabled = state.enabled.filterNot { it in variants(base, state.enabled) }.toSet() + multi,
            selection = selection - base,
        )
    }

    fun selectAll(state: State, known: Set<String>): State {
        return State(
            enabled = state.enabled + known + MULTI + OTHER,
            selection = setOf(ALL_SELECTED),
        )
    }

    /** Lets newly seen languages follow an earlier choice. */
    fun reconcile(state: State, known: Set<String>): State {
        val added = when {
            state.selection.isEmpty() -> emptyList()
            isAll(state.selection) -> known
            else -> known.filter { it !in setOf(MULTI, OTHER) && baseOf(it) in state.selection }
        }
        return state.copy(enabled = state.enabled + added)
    }

    /**
     * Keeps the choice coherent with a per-language toggle, which always wins: a language turned
     * off by hand must not come back the next time languages are reconciled.
     */
    fun manualToggle(state: State, language: String): State {
        val turnedOff = language in state.enabled
        val enabled = if (turnedOff) state.enabled - language else state.enabled + language
        val selection = when {
            !turnedOff -> state.selection
            isAll(state.selection) -> basesOf(enabled) - baseOf(language)
            else -> state.selection - baseOf(language)
        }
        return State(enabled, selection)
    }

    /**
     * Languages the player tries when no preferred audio or subtitle language was set. Empty when
     * the setting says nothing about it, in which case the player keeps using the device language.
     */
    fun playerLanguages(selection: Set<String>, deviceLanguage: String): List<String> {
        if (selection.isEmpty() || isAll(selection)) return emptyList()
        return selection.sortedBy { it != deviceLanguage }
    }

    /** How many sources the language filter currently hides, once the setting is in use. */
    fun <T> hiddenSourceCount(
        selection: Set<String>,
        enabled: Set<String>,
        sources: List<T>,
        lang: (T) -> String,
    ): Int {
        if (selection.isEmpty()) return 0
        return sources.count { lang(it) !in enabled }
    }

    private fun variants(base: String, known: Set<String>): List<String> {
        return known.filter { it !in setOf(MULTI, OTHER) && baseOf(it) == base }
    }

    /**
     * The languages that are on without any exception, which can safely follow new variants.
     * A base with a variant left off, or only a variant on, was narrowed down by hand.
     */
    private fun fullyEnabledBases(enabled: Set<String>, known: Set<String>): Set<String> {
        return basesOf(enabled).filterTo(mutableSetOf()) { base ->
            base in enabled && variants(base, known).all { it in enabled }
        }
    }
}
