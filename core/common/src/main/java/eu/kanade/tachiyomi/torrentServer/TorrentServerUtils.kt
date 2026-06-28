package eu.kanade.tachiyomi.torrentServer

import eu.kanade.tachiyomi.torrentServer.model.BtSets
import eu.kanade.tachiyomi.torrentServer.model.FileStat
import eu.kanade.tachiyomi.torrentServer.model.Torrent
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.net.URLEncoder

object TorrentServerUtils {
    private val preferences: TorrentServerPreferences by injectLazy()

    // The server binds to loopback only (see web/server.go), so the on-device player must reach it
    // via 127.0.0.1. Computed each access so a changed port preference is picked up on next start.
    val hostUrl get() = "http://127.0.0.1:${preferences.port().get()}"

    // The trackers must be separated by comma because it is hardcoded in go-torrent-server
    private val animeTrackers: String
        get() = preferences.trackers().get().split("\n").joinToString(",\n")

    fun setTrackersList() {
        val count = preferences.trackers().get().split("\n").count { it.isNotBlank() }
        logcat(LogPriority.INFO) { "[Torrent] setTrackersList pushing $count trackers" }
        torrServer.TorrServer.addTrackers(animeTrackers)
    }

    /**
     * Pushes the user's cache-size / download-directory / seeding preferences to the running server
     * via the BTSets REST endpoint (the gomobile binding only takes a data path at startup, so these
     * are applied here instead). Bytes = MiB * 1024 * 1024; a blank download dir keeps the in-memory
     * cache; seeding off maps to DisableUpload = true.
     */
    /** Name of the dedicated sub-directory the torrent server writes cache into. */
    const val DATA_SUBDIR = "torrserver"

    @Suppress("MagicNumber")
    fun applyServerSettings() {
        val dir = preferences.downloadDir().get()
        // Always confine torrent data to a dedicated sub-folder we own, never the raw user dir
        // (so clearing the cache can't recursively delete an arbitrary directory the user typed).
        val savePath = if (dir.isNotBlank()) File(dir, DATA_SUBDIR).absolutePath else ""
        val sets = BtSets(
            cacheSize = preferences.cacheSize().get() * 1024 * 1024,
            useDisk = savePath.isNotEmpty(),
            torrentsSavePath = savePath,
            disableUpload = !preferences.enableSeeding().get(),
        )
        logcat(LogPriority.INFO) {
            "[Torrent] applyServerSettings cache=${preferences.cacheSize().get()}MiB " +
                "useDisk=${sets.useDisk} seeding=${!sets.disableUpload}"
        }
        TorrentServerApi.setSettings(sets)
    }

    fun getTorrentPlayLink(torrent: Torrent, index: Int): String {
        val file = findFile(torrent, index)
        val name = file?.let { File(it.path).name } ?: torrent.title
        return "$hostUrl/stream/${name?.urlEncode().orEmpty()}?link=${torrent.hash}&index=$index&play".also {
            logcat(LogPriority.INFO) { "[Torrent] play link (index=$index, file='$name'): $it" }
        }
    }

    private fun findFile(torrent: Torrent, index: Int): FileStat? {
        torrent.file_stats?.forEach {
            if (it.id == index) {
                return it
            }
        }
        return null
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "utf8")
}
