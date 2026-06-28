package eu.kanade.tachiyomi.torrentutils

import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentutils.model.DeadTorrentException
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.SocketTimeoutException
import java.net.URI

object TorrentUtils {
    fun getTorrentInfo(
        url: String,
        title: String,
    ): TorrentInfo {
        // Untrusted extensions supply [url]; the local server will fetch it. Restrict to torrent
        // schemes and refuse links pointing at loopback/private hosts (SSRF guard).
        requireSafeTorrentLink(url)
        logcat(LogPriority.INFO) { "[Torrent] getTorrentInfo title='$title'" }
        try {
            val torrent = TorrentServerApi.addTorrent(url, title, "", "", false)
            val trackers = torrent.trackers ?: emptyList()
            return TorrentInfo(
                title = torrent.title ?: title,
                files = torrent.file_stats?.map { file ->
                    TorrentFile(
                        path = file.path,
                        indexFile = file.id ?: 0,
                        size = file.length,
                        torrentHash = torrent.hash!!,
                        trackers = trackers,
                    )
                } ?: emptyList(),
                hash = torrent.hash!!,
                size = torrent.torrent_size ?: 0L,
                trackers = trackers,
            ).also {
                logcat(LogPriority.INFO) {
                    "[Torrent] getTorrentInfo ok hash=${it.hash} files=${it.files.size}"
                }
            }
        } catch (e: SocketTimeoutException) {
            logcat(LogPriority.ERROR, e) { "[Torrent] getTorrentInfo timed out (dead torrent)" }
            throw DeadTorrentException()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "[Torrent] getTorrentInfo failed" }
            throw e
        }
    }

    /**
     * Accept only `magnet:` links and `http(s)` torrent URLs whose host is public. Rejects other
     * schemes (file://, content://, …) and links aimed at loopback/private/link-local hosts so an
     * untrusted extension can't turn the local server into an SSRF proxy onto the device's network.
     */
    private fun requireSafeTorrentLink(url: String) {
        val lower = url.trim().lowercase()
        if (lower.startsWith("magnet:")) return
        require(lower.startsWith("http://") || lower.startsWith("https://")) {
            "Unsupported torrent link scheme"
        }
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        require(host.isNotEmpty() && !isPrivateHost(host)) {
            "Refusing torrent link to non-public host"
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        return h == "localhost" ||
            h == "::1" ||
            h == "0.0.0.0" ||
            h.startsWith("127.") ||
            h.startsWith("10.") ||
            h.startsWith("192.168.") ||
            h.startsWith("169.254.") ||
            h.matches(Regex("""172\.(1[6-9]|2\d|3[01])\..*"""))
    }
}
