package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.ContentLanguages
import eu.kanade.domain.source.interactor.GetKnownSourceLanguages
import eu.kanade.domain.source.interactor.SetContentLanguage
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ContentLanguageScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val handleBack = LocalBackPress.current

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(AYMR.strings.pref_content_language),
                    navigateUp = { if (handleBack != null) handleBack() else navigator.pop() },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                ContentLanguageDescription()
                ContentLanguagePicker()
            }
        }
    }
}

@Composable
fun ContentLanguageDescription() {
    Text(
        text = stringResource(AYMR.strings.pref_content_language_description),
        modifier = Modifier
            .fillMaxWidth()
            .padding(MaterialTheme.padding.medium)
            .secondaryItemAlpha(),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Not lazy, as onboarding places it inside a scrolling column. There are only a few dozen rows. */
@Composable
fun ContentLanguagePicker() {
    val preferences = remember { Injekt.get<SourcePreferences>() }
    val setContentLanguage = remember { Injekt.get<SetContentLanguage>() }
    val knownFlow = remember { Injekt.get<GetKnownSourceLanguages>().subscribe() }

    val enabled by preferences.enabledLanguages().collectAsState()
    val selection by preferences.contentLanguageSelection().collectAsState()
    val known by knownFlow.collectAsState(emptySet())

    val state = ContentLanguages.State(enabled, selection)
    // Rows keep their place while ticking them. What was already on goes first when opening.
    val initiallyChecked = remember { ContentLanguages.basesOf(enabled) }
    val options = ContentLanguages.options(enabled, known)
    val rows = remember(options) {
        options.sortedWith(
            compareBy<String> { it !in initiallyChecked }.then(LocaleHelper.comparator),
        )
    }

    Column {
        CheckboxItem(
            label = stringResource(AYMR.strings.content_language_all),
            checked = ContentLanguages.isAll(selection),
            onClick = { setContentLanguage.selectAll(known) },
        )
        rows.forEach { base ->
            CheckboxItem(
                label = languageLabel(base),
                checked = ContentLanguages.isChecked(state, base),
                onClick = { setContentLanguage.toggle(base, known) },
            )
        }
    }
}

@Composable
fun contentLanguageSummary(): String {
    val preferences = remember { Injekt.get<SourcePreferences>() }
    val enabled by preferences.enabledLanguages().collectAsState()
    val selection by preferences.contentLanguageSelection().collectAsState()

    if (ContentLanguages.isAll(selection)) return stringResource(AYMR.strings.content_language_all)
    val bases = ContentLanguages.basesOf(enabled)
    if (bases.isEmpty()) return stringResource(MR.strings.multi_lang)
    return bases
        .sortedWith(LocaleHelper.comparator)
        .joinToString { LocaleHelper.getLocalizedDisplayName(it) }
}

/** The language's own name, as in the other language lists, followed by the name in the app's language. */
private fun languageLabel(base: String): String {
    val nativeName = LocaleHelper.getLocalizedDisplayName(base)
    val appName = LocaleHelper.getDisplayName(base)
    return if (nativeName.equals(appName, ignoreCase = true)) nativeName else "$nativeName ($appName)"
}
