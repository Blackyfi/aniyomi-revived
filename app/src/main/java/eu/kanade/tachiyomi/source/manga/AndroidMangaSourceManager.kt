package eu.kanade.tachiyomi.source.manga

import android.content.Context
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.online.HttpSource
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
import tachiyomi.domain.source.manga.model.StubMangaSource
import tachiyomi.domain.source.manga.repository.MangaStubSourceRepository
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.source.local.entries.manga.LocalMangaSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class AndroidMangaSourceManager(
    private val context: Context,
    private val extensionManager: MangaExtensionManager,
    private val sourceRepository: MangaStubSourceRepository,
) : MangaSourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val downloadManager: MangaDownloadManager by injectLazy()

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, MangaSource>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubMangaSource>()

    override val catalogueSources: Flow<List<CatalogueSource>> = sourcesMapFlow
        .onStart { _isInitialized.first { it } }
        .map { it.values.filterIsInstance<CatalogueSource>() }

    init {
        scope.launch {
            // Extensions are scanned off the main thread; until that's done an empty list here
            // would mean "not known yet", not "none installed".
            extensionManager.isInitialized.first { it }
            extensionManager.installedExtensionsFlow
                .collectLatest { extensions ->
                    val mutableMap = ConcurrentHashMap<Long, MangaSource>(
                        mapOf(
                            LocalMangaSource.ID to LocalMangaSource(
                                context,
                                Injekt.get(),
                                Injekt.get(),
                            ),
                        ),
                    )
                    extensions.forEach { extension ->
                        extension.sources.forEach {
                            mutableMap[it.id] = it
                            registerStubSource(StubMangaSource.from(it))
                        }
                    }
                    sourcesMapFlow.value = mutableMap
                    _isInitialized.value = true
                }
        }

        scope.launch {
            sourceRepository.subscribeAllManga()
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

    override fun get(sourceKey: Long): MangaSource? {
        awaitInitializedBlocking()
        return sourcesMapFlow.value[sourceKey]
    }

    override fun getOrStub(sourceKey: Long): MangaSource {
        awaitInitializedBlocking()
        return sourcesMapFlow.value[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getOnlineSources(): List<HttpSource> {
        awaitInitializedBlocking()
        return sourcesMapFlow.value.values.filterIsInstance<HttpSource>()
    }

    override fun getCatalogueSources(): List<CatalogueSource> {
        awaitInitializedBlocking()
        return sourcesMapFlow.value.values.filterIsInstance<CatalogueSource>()
    }

    override fun getStubSources(): List<StubMangaSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubMangaSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubMangaSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubMangaSource(source.id, source.lang, source.name)
            if (dbSource != null) {
                downloadManager.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubMangaSource {
        sourceRepository.getStubMangaSource(id)?.let {
            return it
        }
        extensionManager.getSourceData(id)?.let {
            registerStubSource(it)
            return it
        }
        return StubMangaSource(id = id, lang = "", name = "")
    }
}

private val INIT_WAIT_TIMEOUT = 20.seconds
