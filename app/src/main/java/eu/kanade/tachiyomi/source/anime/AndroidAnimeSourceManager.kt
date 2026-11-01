package eu.kanade.tachiyomi.source.anime

import android.content.Context
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.domain.source.anime.repository.AnimeStubSourceRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class AndroidAnimeSourceManager(
    private val context: Context,
    private val extensionManager: AnimeExtensionManager,
    private val sourceRepository: AnimeStubSourceRepository,
) : AnimeSourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val downloadManager: AnimeDownloadManager by injectLazy()

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, AnimeSource>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubAnimeSource>()

    override val catalogueSources: Flow<List<AnimeCatalogueSource>> = sourcesMapFlow
        .onStart { _isInitialized.first { it } }
        .map { it.values.filterIsInstance<AnimeCatalogueSource>() }

    init {
        scope.launch {
            // Extensions are scanned off the main thread; until that's done an empty list here
            // would mean "not known yet", not "none installed".
            extensionManager.isInitialized.first { it }
            extensionManager.installedExtensionsFlow
                .collectLatest { extensions ->
                    val mutableMap = ConcurrentHashMap<Long, AnimeSource>(
                        mapOf(
                            LocalAnimeSource.ID to LocalAnimeSource(
                                context,
                                Injekt.get(),
                                Injekt.get(),
                                Injekt.get(),
                                Injekt.get(),
                                Injekt.get(),
                            ),
                        ),
                    )
                    extensions.forEach { extension ->
                        extension.sources.forEach {
                            mutableMap[it.id] = it
                            registerStubSource(StubAnimeSource.from(it))
                        }
                    }
                    sourcesMapFlow.value = mutableMap
                    _isInitialized.value = true
                }
        }

        scope.launch {
            sourceRepository.subscribeAllAnime()
                .collectLatest { sources ->
                    sources.forEach {
                        stubSourcesMap[it.id] = it
                    }
                }
        }
    }

    /**
     * Sources are loaded in the background. Callers can't tell "not loaded yet" from "not
     * installed", and would treat a source as missing, so lookups wait for the load to finish.
     * The timeout only guards against a hung extension.
     */
    private fun awaitInitializedBlocking() {
        if (_isInitialized.value) return
        runBlocking { withTimeoutOrNull(INIT_WAIT_TIMEOUT) { _isInitialized.first { it } } }
    }

    override fun get(sourceKey: Long): AnimeSource? {
        awaitInitializedBlocking()
        return sourcesMapFlow.value[sourceKey]
    }

    override fun getOrStub(sourceKey: Long): AnimeSource {
        awaitInitializedBlocking()
        return sourcesMapFlow.value[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getOnlineSources(): List<AnimeHttpSource> {
        awaitInitializedBlocking()
        return sourcesMapFlow.value.values.filterIsInstance<AnimeHttpSource>()
    }

    override fun getCatalogueSources(): List<AnimeCatalogueSource> {
        awaitInitializedBlocking()
        return sourcesMapFlow.value.values.filterIsInstance<AnimeCatalogueSource>()
    }

    override fun getStubSources(): List<StubAnimeSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubAnimeSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubAnimeSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubAnimeSource(source.id, source.lang, source.name)
            if (dbSource != null) {
                downloadManager.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubAnimeSource {
        sourceRepository.getStubAnimeSource(id)?.let {
            return it
        }
        extensionManager.getSourceData(id)?.let {
            registerStubSource(it)
            return it
        }
        return StubAnimeSource(id = id, lang = "", name = "")
    }
}

private val INIT_WAIT_TIMEOUT = 20.seconds
