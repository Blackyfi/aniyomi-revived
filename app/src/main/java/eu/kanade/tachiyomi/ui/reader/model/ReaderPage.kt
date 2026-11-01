package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.torrentServer.model.TorrentLoadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl) {

    open lateinit var chapter: ReaderChapter

    /**
     * What the torrent server reports while this page downloads. Stays null for pages that don't
     * come from a torrent.
     */
    val torrentStatus = MutableStateFlow<TorrentLoadStatus?>(null)
}
