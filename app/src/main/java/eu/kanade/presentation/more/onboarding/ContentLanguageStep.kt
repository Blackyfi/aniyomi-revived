package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import eu.kanade.presentation.more.settings.screen.browse.ContentLanguageDescription
import eu.kanade.presentation.more.settings.screen.browse.ContentLanguagePicker

internal class ContentLanguageStep : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        Column {
            ContentLanguageDescription()
            ContentLanguagePicker()
        }
    }
}
