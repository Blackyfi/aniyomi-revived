package eu.kanade.tachiyomi.torrentServer

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.torrentServer.model.BtSets
import eu.kanade.tachiyomi.torrentServer.model.BtSetsRequest
import eu.kanade.tachiyomi.torrentServer.model.Torrent
import eu.kanade.tachiyomi.torrentServer.model.TorrentRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.IOException

object TorrentServerApi {
    private val network: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    private val hostUrl get() = TorrentServerUtils.hostUrl
    private val jsonMediaType = "application/json".toMediaTypeOrNull()
    private val torrentMediaType = "application/x-bittorrent".toMediaTypeOrNull()

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
        // Don't log the raw link: magnets/.torrent URLs can embed private-tracker passkeys.
        logcat(LogPriority.INFO) { "[Torrent] addTorrent title='$title' save=$save" }
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
        return resp.decodeTorrent("addTorrent")
    }

    /**
     * Adds a torrent from the raw bytes of a .torrent file (fetched by the app rather than by the
     * server, so it goes through the app's network stack: DNS-over-HTTPS, cookies, user agent).
     */
    fun uploadTorrent(file: ByteArray, title: String): Torrent {
        logcat(LogPriority.INFO) { "[Torrent] uploadTorrent title='$title' bytes=${file.size}" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("title", title)
            .addFormDataPart("file", "file.torrent", file.toRequestBody(torrentMediaType))
            .build()
        val resp = network.client.newCall(POST("$hostUrl/torrent/upload", body = body)).execute()
        return resp.decodeTorrent("uploadTorrent")
    }

    private fun Response.decodeTorrent(action: String): Torrent = use { resp ->
        val bodyStr = resp.body.string()
        if (!resp.isSuccessful) {
            logcat(LogPriority.ERROR) { "[Torrent] $action failed HTTP ${resp.code} body=$bodyStr" }
            // The server answers a link it can't load with an empty 400; decoding that would only
            // surface as an opaque JSON error.
            throw IOException("The torrent server couldn't load this torrent (HTTP ${resp.code})")
        }
        json.decodeFromString(Torrent.serializer(), bodyStr).also {
            logcat(LogPriority.INFO) {
                "[Torrent] $action ok hash=${it.hash} files=${it.file_stats?.size ?: 0} size=${it.torrent_size}"
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

    // region Settings
    fun setSettings(sets: BtSets) {
        val req = json.encodeToString(BtSetsRequest.serializer(), BtSetsRequest(action = "set", sets = sets))
        val resp = network.client.newCall(
            POST("$hostUrl/settings", body = req.toRequestBody(jsonMediaType)),
        ).execute()
        if (!resp.isSuccessful) {
            logcat(LogPriority.ERROR) { "[Torrent] setSettings failed HTTP ${resp.code}" }
        }
        resp.close()
    }
    // endregion
}
