package eu.kanade.tachiyomi.torrentServer.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the torrent server's BTSets that the app controls. Field names use the Go struct's
 * PascalCase JSON keys. Fields left out of the payload stay at the server's failsafe defaults
 * (e.g. ConnectionsLimit, RetrackersMode), so only the user-facing knobs are sent.
 */
@Serializable
data class BtSets(
    @SerialName("CacheSize") val cacheSize: Long,
    @SerialName("UseDisk") val useDisk: Boolean,
    @SerialName("TorrentsSavePath") val torrentsSavePath: String,
    @SerialName("RemoveCacheOnDrop") val removeCacheOnDrop: Boolean = false,
    @SerialName("DisableUpload") val disableUpload: Boolean,
)

/** Request envelope for `POST /settings` (action = get | set | def). */
@Serializable
data class BtSetsRequest(
    @SerialName("action") val action: String = "set",
    @SerialName("sets") val sets: BtSets,
)
