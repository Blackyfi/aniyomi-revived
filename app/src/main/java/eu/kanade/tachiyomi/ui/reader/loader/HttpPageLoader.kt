package eu.kanade.tachiyomi.ui.reader.loader

import android.os.SystemClock
import aniyomi.util.DataSaver
import aniyomi.util.DataSaver.Companion.getImage
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.manga.toDomainChapter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.torrentServer.model.TorrentLoadStatus
import eu.kanade.tachiyomi.torrentServer.model.toLoadStatus
import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.ExtensionErrorStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Loader used to load chapters from an online source.
 */
internal class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = Injekt.get(),
    // SY -->
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    // SY <--
    private val readerPreferences: ReaderPreferences = Injekt.get(),
) : PageLoader() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A queue used to manage requests a few at a time while allowing priorities.
     */
    private val queue = PriorityBlockingQueue<PriorityPage>()

    // SY -->
    private val dataSaver = DataSaver(source, sourcePreferences)
    // SY <--

    @Volatile
    private var torrentDeadUntilMs = 0L

    private val torrentDownloads = Semaphore(MAX_TORRENT_DOWNLOADS)
    private val sourceDownloads = Semaphore(MAX_SOURCE_DOWNLOADS)

    /** The cache refuses a second writer, which a chapter repeating an image would otherwise be. */
    private val imageLocks = ConcurrentHashMap<String, Mutex>()

    private var torrentKeepAliveJob: Job? = null

    @Volatile
    private var lastPageRequestMs = SystemClock.elapsedRealtime()

    init {
        scope.launchIO {
            while (true) {
                // Holding one of each until a page is picked means the pick happens when it can
                // start, so that the page on screen overtakes the preloads queued behind it.
                torrentDownloads.acquire()
                sourceDownloads.acquire()
                val page = runInterruptible { queue.take() }.page
                if (page.status != Page.State.QUEUE) {
                    torrentDownloads.release()
                    sourceDownloads.release()
                    continue
                }

                // A torrent is served from the device, so hosts have no stake in it, and pages stuck on
                // rare pieces shouldn't hold up others that have the pieces they need.
                val isTorrent = page.imageUrl?.let(TorrentServerUtils::streamHash) != null
                val slots = if (isTorrent) torrentDownloads else sourceDownloads
                (if (isTorrent) sourceDownloads else torrentDownloads).release()

                // Before the next take(), which would otherwise see this page still queued.
                page.status = if (page.imageUrl.isNullOrEmpty()) Page.State.LOAD_PAGE else Page.State.DOWNLOAD_IMAGE
                launchIO {
                    try {
                        internalLoadPage(page)
                    } finally {
                        slots.release()
                    }
                }
            }
        }
    }

    override var isLocal: Boolean = false

    /**
     * Returns the page list for a chapter. It tries to return the page list from the local cache,
     * otherwise fallbacks to network.
     */
    override suspend fun getPages(): List<ReaderPage> {
        val pages = try {
            chapterCache.getPageListFromCache(chapter.chapter.toDomainChapter()!!)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            runCatching { source.getPageList(chapter.chapter) }
                .onFailure { err ->
                    if (err !is CancellationException) {
                        ExtensionErrorStorage.record(
                            source.id,
                            source.name,
                            "Load pages",
                            err,
                            detail = chapter.chapter.name,
                        )
                    }
                }
                .getOrThrow()
        }
        return pages.mapIndexed { index, page ->
            // Don't trust sources and use our own indexing
            ReaderPage(index, page.url, page.imageUrl)
        }
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = withIOContext {
        lastPageRequestMs = SystemClock.elapsedRealtime()
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status == Page.State.READY &&
            imageUrl != null &&
            !chapterCache.isImageInCache(
                imageUrl,
            )
        ) {
            page.status = Page.State.QUEUE
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status == Page.State.ERROR) {
            page.status = Page.State.QUEUE
        }

        val queuedPages = mutableListOf<PriorityPage>()
        if (page.status == Page.State.QUEUE) {
            queuedPages += PriorityPage(page, 1).also { queue.offer(it) }
        }
        queuedPages += preloadNextPages(page, readerPreferences.preloadPages().get())

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                queuedPages.forEach {
                    if (it.page.status == Page.State.QUEUE) {
                        queue.remove(it)
                    }
                }
            }
        }
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        torrentDeadUntilMs = 0L
        if (page.status == Page.State.READY) {
            // The page downloaded but failed to decode. The queue skips READY pages, so without
            // this the retry button does nothing and the bad file stays cached.
            scope.launchIO {
                page.imageUrl?.let(chapterCache::removeImageFromCache)
                page.status = Page.State.QUEUE
                queue.offer(PriorityPage(page, 2))
            }
            return
        }
        if (page.status == Page.State.ERROR) {
            page.status = Page.State.QUEUE
        }
        queue.offer(PriorityPage(page, 2))
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        queue.clear()

        // Cache current page list progress for online chapters to allow a faster reopen
        chapter.pages?.let { pages ->
            launchIO {
                try {
                    // Convert to pages without reader information
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(
                        chapter.chapter.toDomainChapter()!!,
                        pagesToSave,
                    )
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Preloads the given [amount] of pages after the [currentPage] with a lower priority.
     *
     * @return a list of [PriorityPage] that were added to the [queue]
     */
    private fun preloadNextPages(currentPage: ReaderPage, amount: Int): List<PriorityPage> {
        val pageIndex = currentPage.index
        val pages = currentPage.chapter.pages ?: return emptyList()
        if (pageIndex == pages.lastIndex) return emptyList()

        return pages
            .subList(pageIndex + 1, min(pageIndex + 1 + amount, pages.size))
            .mapNotNull {
                if (it.status == Page.State.QUEUE) {
                    PriorityPage(it, 0).apply { queue.offer(this) }
                } else {
                    null
                }
            }
    }

    /**
     * Downloads an image served by the local torrent server, riding out the wait for peers and
     * publishing the torrent's state on the page in the meantime.
     */
    private suspend fun downloadTorrentImage(page: ReaderPage, imageUrl: String, hash: String) = coroutineScope {
        page.torrentStatus.value = null
        if (SystemClock.elapsedRealtime() < torrentDeadUntilMs) {
            page.torrentStatus.value = TorrentLoadStatus(dead = true)
            throw IOException("The torrent has no peers")
        }

        keepTorrentAlive(imageUrl)
        val policy = TorrentRetryPolicy(SystemClock.elapsedRealtime())
        val statusJob = launch { publishTorrentStatus(page, hash, policy) }
        try {
            while (true) {
                try {
                    // Not through the data saver: those proxies fetch the URL themselves, and this
                    // one only exists on the device.
                    chapterCache.putImageToCache(imageUrl, source.getImage(page))
                    page.torrentStatus.value = null
                    return@coroutineScope
                } catch (e: IOException) {
                    if (!policy.shouldRetry(e, SystemClock.elapsedRealtime())) throw e
                    logcat(LogPriority.INFO) { "[Torrent] page ${page.number} still waiting: ${e.message}" }
                    delay(TORRENT_RETRY_DELAY_MS)
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException && policy.looksDead(SystemClock.elapsedRealtime())) {
                torrentDeadUntilMs = SystemClock.elapsedRealtime() + TORRENT_DEAD_WINDOW_MS
                page.torrentStatus.value = TorrentLoadStatus(dead = true)
            }
            throw e
        } finally {
            statusJob.cancel()
        }
    }

    /**
     * The server drops a torrent, and its peers, seconds after the last read. Anyone taking longer
     * than that over the pages already fetched would wait for peers all over again on the next batch,
     * so this keeps the torrent loaded while the chapter is being read, and no longer: it ends soon
     * after page changes stop, e.g. when the reader is left in the background.
     */
    private fun keepTorrentAlive(imageUrl: String) = synchronized(this) {
        if (torrentKeepAliveJob?.isActive == true) return
        torrentKeepAliveJob = scope.launchIO {
            while (SystemClock.elapsedRealtime() - lastPageRequestMs < TORRENT_KEEP_ALIVE_MAX_IDLE_MS) {
                delay(TORRENT_KEEP_ALIVE_INTERVAL_MS)
                runCatching {
                    runInterruptible {
                        if (TorrentUtils.keepServerAlive()) {
                            // With &stat the server answers with the status instead of the file, and
                            // pushes the torrent's expiry back the same way a playback request does.
                            Injekt.get<NetworkHelper>().client.newCall(GET("$imageUrl&stat")).execute().close()
                        }
                    }
                }
            }
        }
    }

    private suspend fun publishTorrentStatus(page: ReaderPage, hash: String, policy: TorrentRetryPolicy) {
        delay(TORRENT_STATUS_FIRST_DELAY_MS)
        page.torrentStatus.value = TorrentLoadStatus()
        while (true) {
            // Fails while the server is restarting or hasn't been handed the torrent yet.
            runCatching { runInterruptible { TorrentServerApi.getTorrent(hash) } }.getOrNull()?.let { torrent ->
                val status = torrent.toLoadStatus()
                if (status.activePeers > 0) policy.sawPeers = true
                page.torrentStatus.value = status
            }
            delay(TORRENT_STATUS_INTERVAL_MS)
        }
    }

    /**
     * Loads the page, retrieving the image URL and downloading the image if necessary.
     * Downloaded images are stored in the chapter cache.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun internalLoadPage(page: ReaderPage) {
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LOAD_PAGE
                page.imageUrl = source.getImageUrl(page)
            }
            val imageUrl = page.imageUrl!!

            imageLocks.getOrPut(imageUrl) { Mutex() }.withLock {
                if (!chapterCache.isImageInCache(imageUrl)) {
                    page.status = Page.State.DOWNLOAD_IMAGE
                    val torrentHash = TorrentServerUtils.streamHash(imageUrl)
                    if (torrentHash != null) {
                        downloadTorrentImage(page, imageUrl, torrentHash)
                    } else {
                        val imageResponse = source.getImage(page, dataSaver)
                        chapterCache.putImageToCache(imageUrl, imageResponse)
                    }
                }
            }

            page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
            page.status = Page.State.READY
        } catch (e: Throwable) {
            page.status = Page.State.ERROR
            if (e is CancellationException) {
                throw e
            }
            // Image-load failures were otherwise silent; log so reader "loads
            // forever"/broken-image issues are diagnosable from the Debug logs.
            logcat(LogPriority.ERROR, e) {
                "Failed to load page ${page.number} (imageUrl=${page.imageUrl}) from source ${source.name}"
            }
        }
    }
}

private const val MAX_TORRENT_DOWNLOADS = 3

/** The same number the downloader runs against sources. */
private const val MAX_SOURCE_DOWNLOADS = 2
private const val TORRENT_KEEP_ALIVE_INTERVAL_MS = 60_000L
private const val TORRENT_KEEP_ALIVE_MAX_IDLE_MS = 10 * 60_000L

/** Delay before the first status, so that a page the server already has doesn't flash one. */
private const val TORRENT_STATUS_FIRST_DELAY_MS = 1_000L
private const val TORRENT_STATUS_INTERVAL_MS = 1_500L
private const val TORRENT_RETRY_DELAY_MS = 1_000L

/** How long pages of a torrent found dead fail at once, rather than each waiting it out again. */
private const val TORRENT_DEAD_WINDOW_MS = 30_000L

/**
 * Data class used to keep ordering of pages in order to maintain priority.
 */
private class PriorityPage(
    val page: ReaderPage,
    val priority: Int,
) : Comparable<PriorityPage> {
    companion object {
        private val idGenerator = AtomicInteger()
    }

    private val identifier = idGenerator.incrementAndGet()

    override fun compareTo(other: PriorityPage): Int {
        val p = other.priority.compareTo(priority)
        return if (p != 0) p else identifier.compareTo(other.identifier)
    }
}
