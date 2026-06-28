package eu.kanade.tachiyomi.data.torrentServer.service

import android.content.Context
import eu.kanade.tachiyomi.torrentServer.TorrentServerStarter

/**
 * App-side [TorrentServerStarter] backed by the foreground [TorrentServerService].
 *
 * Lets `core/common` (e.g. TorrentUtils, while an extension builds an episode list from a .torrent)
 * bring the server up without depending on the app module.
 */
class TorrentServerStarterImpl(private val context: Context) : TorrentServerStarter {

    override fun ensureRunning(): Boolean {
        if (!TorrentServerService.isRunning) {
            TorrentServerService.start(context)
        }
        return TorrentServerService.wait(WAIT_SECONDS)
    }

    private companion object {
        private const val WAIT_SECONDS = 10
    }
}
