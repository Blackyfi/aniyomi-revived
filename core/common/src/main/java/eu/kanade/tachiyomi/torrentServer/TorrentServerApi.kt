package eu.kanade.tachiyomi.torrentServer

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.torrentServer.model.Torrent
import eu.kanade.tachiyomi.torrentServer.model.TorrentRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy

object TorrentServerApi {
    private val network: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    private val hostUrl get() = TorrentServerUtils.hostUrl
    private val jsonMediaType = "application/json".toMediaTypeOrNull()

    @Suppress("TooGenericExceptionCaught")
    fun echo(): String {
        return try {
            network.client.newCall(GET("$hostUrl/echo")).execute().body.string()
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { e.toString() }
            ""
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun shutdown(): String {
        return try {
            network.client.newCall(GET("$hostUrl/shutdown")).execute().body.string()
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { e.toString() }
            ""
        }
    }

    // region Torrents
    fun addTorrent(
        link: String,
        title: String,
        poster: String = "",
        data: String = "",
        save: Boolean,
    ): Torrent {
        logcat(LogPriority.INFO) { "[Torrent] addTorrent title='$title' save=$save link=$link" }
        val req = TorrentRequest(
            action = "add",
            link = link,
            title = title,
            poster = poster,
            data = data,
            saveToDb = save,
        ).toString()
        val resp = network.client.newCall(
            POST("$hostUrl/torrents", body = req.toRequestBody(jsonMediaType)),
        ).execute()
        val bodyStr = resp.body.string()
        if (!resp.isSuccessful) {
            logcat(LogPriority.ERROR) {
                "[Torrent] addTorrent failed HTTP ${resp.code} from $hostUrl/torrents body=$bodyStr"
            }
        }
        return json.decodeFromString(Torrent.serializer(), bodyStr).also {
            logcat(LogPriority.INFO) {
                "[Torrent] addTorrent ok hash=${it.hash} files=${it.file_stats?.size ?: 0} size=${it.torrent_size}"
            }
        }
    }

    fun getTorrent(hash: String): Torrent {
        val req = TorrentRequest(action = "get", hash = hash).toString()
        val resp = network.client.newCall(
            POST("$hostUrl/torrents", body = req.toRequestBody(jsonMediaType)),
        ).execute()
        return json.decodeFromString(Torrent.serializer(), resp.body.string())
    }

    fun remTorrent(hash: String) {
        val req = TorrentRequest(action = "rem", hash = hash).toString()
        network.client.newCall(
            POST("$hostUrl/torrents", body = req.toRequestBody(jsonMediaType)),
        ).execute().close()
    }

    fun listTorrent(): List<Torrent> {
        val req = TorrentRequest(action = "list").toString()
        val resp = network.client.newCall(
            POST("$hostUrl/torrents", body = req.toRequestBody(jsonMediaType)),
        ).execute()
        return json.decodeFromString(ListSerializer(Torrent.serializer()), resp.body.string())
    }
    // endregion
}
