package eu.kanade.domain.source

import eu.kanade.domain.source.ContentLanguages.State
import eu.kanade.domain.source.interactor.SetContentLanguage
import eu.kanade.domain.source.interactor.ToggleLanguage
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

class ContentLanguagesTest {

    private val known = setOf("all", "other", "en", "fr", "pt", "pt-BR", "zh-Hans", "zh-Hant")

    @Test
    fun `base languages leave out what isn't a language`() {
        ContentLanguages.basesOf(listOf("all", "other", "", "en", "pt-BR", "pt_PT", "zh-Hans")) shouldBe
            setOf("en", "pt", "zh")
    }

    @Test
    fun `first use keeps multi-language sources even if they were off`() {
        val result = ContentLanguages.toggle(State(setOf("en"), emptySet()), "fr", known)

        result.enabled shouldBe setOf("all", "en", "fr")
    }

    @Test
    fun `a language turns on with all its variants`() {
        val result = ContentLanguages.toggle(State(setOf("all", "en"), emptySet()), "zh", known)

        result.enabled shouldBe setOf("all", "en", "zh", "zh-Hans", "zh-Hant")
        result.selection shouldBe setOf("en", "zh")
    }

    @Test
    fun `a language turns off with all its variants but not the others`() {
        val state = State(setOf("all", "en", "pt", "pt-BR"), setOf("en", "pt"))

        ContentLanguages.toggle(state, "pt", known) shouldBe State(setOf("all", "en"), setOf("en"))
    }

    @Test
    fun `the last language can't be turned off`() {
        val state = State(setOf("all", "en"), setOf("en"))

        ContentLanguages.toggle(state, "en", known) shouldBe state
    }

    @Test
    fun `a language narrowed down by hand isn't widened when adding another`() {
        val result = ContentLanguages.toggle(State(setOf("all", "pt-BR", "en"), emptySet()), "fr", known)

        result.enabled shouldBe setOf("all", "pt-BR", "en", "fr")
        ContentLanguages.reconcile(result, known + "pt").enabled shouldBe result.enabled
    }

    @Test
    fun `multi-language sources turned off by hand stay off`() {
        val state = State(setOf("en"), setOf("en"))

        ContentLanguages.toggle(state, "fr", known).enabled shouldBe setOf("en", "fr")
    }

    @Test
    fun `all languages includes everything known and later languages`() {
        val all = ContentLanguages.selectAll(State(setOf("en"), emptySet()), known)

        all.enabled shouldBe known
        ContentLanguages.reconcile(all, known + "ko").enabled shouldBe known + "ko"
    }

    @Test
    fun `picking a language while all languages is on narrows down to it`() {
        val all = ContentLanguages.selectAll(State(setOf("en"), emptySet()), known)

        ContentLanguages.toggle(all, "pt", known) shouldBe State(setOf("all", "pt", "pt-BR"), setOf("pt"))
    }

    @Test
    fun `no language is checked while all languages is on`() {
        val all = ContentLanguages.selectAll(State(setOf("en"), emptySet()), known)

        ContentLanguages.isChecked(all, "en") shouldBe false
    }

    @Test
    fun `new variants follow the choice`() {
        val state = State(setOf("all", "pt"), setOf("pt"))

        ContentLanguages.reconcile(state, known).enabled shouldBe setOf("all", "pt", "pt-BR")
    }

    @Test
    fun `nothing changes while the setting was never used`() {
        val state = State(setOf("all", "en"), emptySet())

        ContentLanguages.reconcile(state, known) shouldBe state
        ContentLanguages.playerLanguages(state.selection, "en") shouldBe emptyList()
        ContentLanguages.hiddenSourceCount(state.selection, state.enabled, listOf("fr")) { it } shouldBe 0
    }

    @Test
    fun `a variant turned off by hand doesn't come back`() {
        val state = State(setOf("all", "pt", "pt-BR"), setOf("pt"))

        val result = ContentLanguages.manualToggle(state, "pt-BR")

        result shouldBe State(setOf("all", "pt"), emptySet())
        ContentLanguages.reconcile(result, known) shouldBe result
    }

    @Test
    fun `a language turned off by hand leaves all languages`() {
        val all = ContentLanguages.selectAll(State(setOf("en"), emptySet()), known)

        val result = ContentLanguages.manualToggle(all, "fr")

        result.selection shouldBe setOf("en", "pt", "zh")
        ContentLanguages.reconcile(result, known).enabled shouldBe known - "fr"
    }

    @Test
    fun `multi-language sources turned off by hand leave all languages too`() {
        val all = ContentLanguages.selectAll(State(setOf("en"), emptySet()), known)

        val result = ContentLanguages.manualToggle(all, "all")

        ContentLanguages.reconcile(result, known).enabled shouldBe known - "all"
    }

    @Test
    fun `the device language comes first for the player`() {
        ContentLanguages.playerLanguages(setOf("en", "fr"), "fr") shouldBe listOf("fr", "en")
        ContentLanguages.playerLanguages(setOf("fr", "de"), "en").toSet() shouldBe setOf("fr", "de")
        ContentLanguages.playerLanguages(setOf(ContentLanguages.ALL_SELECTED), "en") shouldBe emptyList()
    }

    @Test
    fun `hidden sources are counted once in use`() {
        val sources = listOf("en", "fr", "all", "de")

        ContentLanguages.hiddenSourceCount(setOf("en"), setOf("all", "en"), sources) { it } shouldBe 2
    }

    @Test
    fun `options cover what is available and what is on`() {
        val options = ContentLanguages.options(setOf("all", "xx-YY"), setOf("all", "other", "kk"))

        (options - ContentLanguages.COMMON) shouldBe setOf("xx", "kk")
    }

    @Test
    fun `an untouched preference stays unset`() {
        val preferences = preferences(enabled = null, selection = null)

        SetContentLanguage(preferences).reconcile(known)

        preferences.enabledLanguages().isSet() shouldBe false
    }

    @Test
    fun `the per-language toggle updates the shared preference`() {
        val preferences = preferences(enabled = setOf("all", "en", "fr"), selection = setOf("en", "fr"))

        ToggleLanguage(preferences).await("fr")

        preferences.enabledLanguages().get() shouldBe setOf("all", "en")
        preferences.contentLanguageSelection().get() shouldBe setOf("en")
    }

    private fun preferences(enabled: Set<String>?, selection: Set<String>?): SourcePreferences {
        val enabledPref = InMemoryPreference("source_languages", enabled, setOf("all", "en"))
        val selectionPref = InMemoryPreference("content_language_selection", selection, emptySet<String>())
        return mockk {
            every { enabledLanguages() } returns enabledPref
            every { contentLanguageSelection() } returns selectionPref
        }
    }
}
