package eu.kanade.tachiyomi.torrentutils

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerStarter
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.torrentServer.model.Torrent
import eu.kanade.tachiyomi.torrentutils.model.DeadTorrentException
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLEncoder

object TorrentUtils {
    private val torrentServerStarter: TorrentServerStarter by injectLazy()
    private val network: NetworkHelper by injectLazy()

    fun getTorrentInfo(
        url: String,
        title: String,
    ): TorrentInfo {
        // Untrusted extensions supply [url]. Restrict to torrent schemes and refuse links pointing
        // at loopback/private hosts (SSRF guard).
        requireSafeTorrentLink(url)
        // Extensions call this while building the episode list (well before the player opens), but
        // the server is otherwise only started on playback. Bring it up here so adding the torrent
        // doesn't fail with "connection refused" to 127.0.0.1:<port>.
        if (!torrentServerStarter.ensureRunning()) {
            logcat(LogPriority.ERROR) { "[Torrent] getTorrentInfo: torrent server did not start in time" }
            throw DeadTorrentException()
        }
        logcat(LogPriority.INFO) { "[Torrent] getTorrentInfo title='$title'" }
        try {
            val torrent = addToServer(url.trim(), title)
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
     * Local URL that serves the raw bytes of [file] from the torrent server, for sources that show
     * a torrent's files directly (e.g. images in a reader) rather than through the player.
     *
     * The link carries the torrent's magnet (hash + trackers), so if the server was restarted
     * since [getTorrentInfo] loaded the torrent, the first request re-adds it. Call
     * [keepServerAlive] before each request so the server is up.
     */
    fun getStreamUrl(file: TorrentFile): String {
        val magnet = file.toMagnetURI().substringBefore("&index=")
        val name = URLEncoder.encode(File(file.path).name, "UTF-8")
        return "${TorrentServerUtils.hostUrl}/stream/$name" +
            "?link=${URLEncoder.encode(magnet, "UTF-8")}&index=${file.indexFile}&play"
    }

    /**
     * Starts the torrent server if needed and postpones its idle stop. Blocking (waits for the
     * server to answer); call it off the main thread, e.g. from an interceptor.
     *
     * @return true if the server is reachable.
     */
    fun keepServerAlive(): Boolean = torrentServerStarter.ensureRunning()

    /**
     * Magnets go to the server as-is. A .torrent link is downloaded here, through the app's own
     * client, and its bytes uploaded: the native server resolves hosts with the system DNS, which
     * ISP blocklists answer with a sinkhole, while the app (and the extension that found the link)
     * may be using DNS-over-HTTPS. It also turns a dead link into a readable error instead of the
     * server's empty 400.
     */
    private fun addToServer(link: String, title: String): Torrent {
        if (link.startsWith("magnet:", ignoreCase = true)) {
            return TorrentServerApi.addTorrent(link, title, "", "", false)
        }
        network.client.newCall(GET(link)).execute().use { response ->
            // OkHttp can't follow a redirect to a magnet: link and hands back the 3xx instead.
            val location = response.header("Location")
            if (response.isRedirect && location != null && location.startsWith("magnet:", ignoreCase = true)) {
                return TorrentServerApi.addTorrent(location, title, "", "", false)
            }
            if (!response.isSuccessful) {
                throw IOException("Couldn't download the torrent file (HTTP ${response.code})")
            }
            val source = response.body.source()
            source.request(MAX_TORRENT_FILE_BYTES + 1)
            if (source.buffer.size > MAX_TORRENT_FILE_BYTES) {
                throw IOException("The torrent file is too large")
            }
            val bytes = source.buffer.readByteArray()
            // A bencoded torrent is a dictionary; anything else is an error or login page.
            if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) {
                throw IOException("The link didn't return a torrent file")
            }
            return TorrentServerApi.uploadTorrent(bytes, title)
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
        // Parse with OkHttp's lenient HttpUrl rather than java.net.URI: torrent download links
        // routinely carry spaces and `[ ]` in the filename portion, which make URI() throw and
        // would otherwise reject a perfectly public host. Only refuse links we can positively
        // resolve to a private/loopback host.
        val host = url.trim().toHttpUrlOrNull()?.host
        require(!host.isNullOrEmpty() && !isPrivateHost(host)) {
            "Refusing torrent link to non-public host"
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true

        val isIpLiteral = h.contains(':') || h.matches(IPV4_LITERAL)
        if (isIpLiteral) {
            return try {
                isPrivateAddress(InetAddress.getByName(h))
            } catch (e: Exception) {
                true // unparseable IP literal → refuse
            }
        }

        // Hostnames aren't resolved here: the app's client fetches .torrent links itself (the
        // server never sees them), so a lookup would add nothing over any extension request, and
        // ISP DNS blocks answer with ::1, which made this refuse perfectly public torrent hosts.
        return false
    }

    private fun isPrivateAddress(addr: InetAddress): Boolean {
        val bytes = addr.address
        return addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            // 10/8, 172.16/12, 192.168/16
            addr.isLinkLocalAddress ||
            // 169.254/16, fe80::/10
            addr.isAnyLocalAddress ||
            // 0.0.0.0, ::
            // IPv6 unique-local fc00::/7, which isSiteLocalAddress does not cover
            (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC)
    }

    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    // Real .torrent files are a few MB at most even for huge multi-file packs.
    private const val MAX_TORRENT_FILE_BYTES = 32L * 1024 * 1024
}
