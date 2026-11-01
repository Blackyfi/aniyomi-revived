package eu.kanade.tachiyomi.torrentServer.model

/**
 * What the torrent server reports while something waits on it, for showing to the user.
 *
 * @property dead the wait was given up without ever reaching a peer.
 */
data class TorrentLoadStatus(
    val activePeers: Int = 0,
    val totalPeers: Int = 0,
    val bytesPerSecond: Long = 0,
    val dead: Boolean = false,
)

fun Torrent.toLoadStatus() = TorrentLoadStatus(
    activePeers = active_peers ?: 0,
    totalPeers = total_peers ?: 0,
    bytesPerSecond = download_speed?.toLong() ?: 0,
)
