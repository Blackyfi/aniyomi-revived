package eu.kanade.tachiyomi.ui.player.utils

import androidx.core.os.LocaleListCompat
import eu.kanade.domain.source.ContentLanguages
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.ui.player.PlayerViewModel.VideoTrack
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class TrackSelect(
    private val subtitlePreferences: SubtitlePreferences = Injekt.get(),
    private val audioPreferences: AudioPreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) {

    fun getPreferredTrackIndex(tracks: List<VideoTrack>, subtitle: Boolean = true): VideoTrack? {
        val prefLangs = if (subtitle) {
            subtitlePreferences.preferredSubLanguages().get()
        } else {
            audioPreferences.preferredAudioLanguages().get()
        }.split(",").filter(String::isNotEmpty).map(String::trim)

        val whitelist = if (subtitle) {
            subtitlePreferences.subtitleWhitelist().get()
        } else {
            ""
        }.split(",").filter(String::isNotEmpty).map(String::trim)

        val blacklist = if (subtitle) {
            subtitlePreferences.subtitleBlacklist().get()
        } else {
            ""
        }.split(",").filter(String::isNotEmpty).map(String::trim)

        val deviceLocale = LocaleListCompat.getDefault()[0]!!
        // A preferred language set for the player always wins over the content language.
        // Audio keeps the device language: reading a language doesn't mean wanting its dub.
        val locales = prefLangs.map(::Locale)
            .ifEmpty {
                if (!subtitle) return@ifEmpty emptyList()
                ContentLanguages.playerLanguages(
                    sourcePreferences.contentLanguageSelection().get(),
                    deviceLocale.language,
                )
                    .map(::Locale)
                    .filter(::hasIso3Language)
            }
            .ifEmpty { listOf(deviceLocale) }

        val chosenLocale = locales.firstOrNull { locale ->
            tracks.any { t -> containsLang(t, locale) }
        } ?: return null

        val filtered = tracks.withIndex()
            .filterNot { (_, track) ->
                blacklist.any { track.name.contains(it, true) }
            }
            .filter { (_, track) ->
                containsLang(track, chosenLocale)
            }

        return filtered.firstOrNull { (_, track) ->
            whitelist.any { track.name.contains(it, true) }
        }?.value ?: filtered.getOrNull(0)?.value
    }

    /** [containsLang] throws for a language without one, which a source's lang can be. */
    private fun hasIso3Language(locale: Locale): Boolean {
        return runCatching { locale.isO3Language }.isSuccess
    }

    private fun containsLang(track: VideoTrack, locale: Locale): Boolean {
        val localName = locale.getDisplayName(locale)
        val englishName = locale.getDisplayName(Locale.ENGLISH).substringBefore(" (")
        val langRegex = Regex("""\b${locale.isO3Language}|${locale.language}\b""", RegexOption.IGNORE_CASE)

        return track.name.contains(localName, true) ||
            track.name.contains(englishName, true) ||
            track.language?.let { langRegex.find(it) != null } == true
    }
}
