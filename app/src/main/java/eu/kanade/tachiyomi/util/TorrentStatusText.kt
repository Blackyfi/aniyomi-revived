package eu.kanade.tachiyomi.util

import android.content.Context
import android.text.format.Formatter
import eu.kanade.tachiyomi.torrentServer.model.TorrentLoadStatus
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.aniyomi.AYMR

/**
 * One line telling the user what a torrent is waiting on.
 */
fun Context.torrentStatusText(status: TorrentLoadStatus): String {
    return when {
        status.activePeers > 0 && status.bytesPerSecond > 0 -> stringResource(
            AYMR.strings.torrent_status_downloading,
            status.activePeers,
            Formatter.formatShortFileSize(this, status.bytesPerSecond),
        )
        status.activePeers > 0 -> stringResource(AYMR.strings.torrent_status_waiting, status.activePeers)
        status.totalPeers > 0 -> stringResource(AYMR.strings.torrent_status_peers_found, status.totalPeers)
        else -> stringResource(AYMR.strings.torrent_status_connecting)
    }
}
