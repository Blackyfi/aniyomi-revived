package eu.kanade.tachiyomi.torrentServer

/**
 * Brings the local torrent server up on demand.
 *
 * Lives in `core/common` so [eu.kanade.tachiyomi.torrentutils.TorrentUtils] can require the server
 * before talking to it (e.g. while an extension builds an episode list from a .torrent), without
 * `core/common` depending on the app module's foreground `TorrentServerService`. The app registers
 * the concrete implementation in Injekt at startup.
 */
interface TorrentServerStarter {
    /**
     * Ensures the local torrent server is running and reachable.
     *
     * Blocking (polls the REST endpoint); must be called from a background thread.
     *
     * @return true if the server responded within the timeout, false otherwise.
     */
    fun ensureRunning(): Boolean
}
