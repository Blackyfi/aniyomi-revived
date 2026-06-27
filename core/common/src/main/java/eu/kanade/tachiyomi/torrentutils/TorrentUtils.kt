package eu.kanade.tachiyomi.torrentutils

import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentutils.model.DeadTorrentException
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.SocketTimeoutException

object TorrentUtils {
    fun getTorrentInfo(
        url: String,
        title: String,
    ): TorrentInfo {
        logcat(LogPriority.INFO) { "[Torrent] getTorrentInfo title='$title' url=$url" }
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
            logcat(LogPriority.ERROR, e) { "[Torrent] getTorrentInfo timed out (dead torrent) url=$url" }
            throw DeadTorrentException()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "[Torrent] getTorrentInfo failed url=$url" }
            throw e
        }
    }
}
