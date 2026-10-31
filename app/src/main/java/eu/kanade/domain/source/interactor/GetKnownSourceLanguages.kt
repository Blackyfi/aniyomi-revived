package eu.kanade.domain.source.interactor

import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Every source language the app currently knows of, available or installed, manga or anime. */
class GetKnownSourceLanguages(
    private val mangaExtensionManager: MangaExtensionManager,
    private val animeExtensionManager: AnimeExtensionManager,
) {

    fun subscribe(): Flow<Set<String>> {
        return combine(
            mangaExtensionManager.availableExtensionsFlow,
            mangaExtensionManager.installedExtensionsFlow,
            animeExtensionManager.availableExtensionsFlow,
            animeExtensionManager.installedExtensionsFlow,
        ) { mangaAvailable, mangaInstalled, animeAvailable, animeInstalled ->
            buildSet {
                // Use the source lang as some aren't present on the extension level.
                mangaAvailable.forEach { ext -> addAll(ext.sources.map { it.lang }.ifEmpty { listOf(ext.lang) }) }
                mangaInstalled.forEach { ext -> addAll(ext.sources.map { it.lang }.ifEmpty { listOf(ext.lang) }) }
                animeAvailable.forEach { ext -> addAll(ext.sources.map { it.lang }.ifEmpty { listOf(ext.lang) }) }
                animeInstalled.forEach { ext -> addAll(ext.sources.map { it.lang }.ifEmpty { listOf(ext.lang) }) }
            }
        }
            .distinctUntilChanged()
    }
}
