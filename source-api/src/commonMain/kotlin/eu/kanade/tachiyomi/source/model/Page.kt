package eu.kanade.tachiyomi.source.model

import android.net.Uri
import eu.kanade.tachiyomi.network.ProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
open class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    // ABI-CRITICAL: `uri` MUST stay as the 4th primary-constructor parameter to match the
    // extensions-lib (eu.kanade.tachiyomi.source.model.Page is `Page(index, url, imageUrl, uri)`)
    // and upstream Aniyomi/Mihon. Extensions call `Page(index, imageUrl = ...)` with `uri`
    // defaulted, which the compiler lowers to the synthetic default-args constructor
    // `<init>(I, String, String, Uri, I, DefaultConstructorMarker)`. Removing `uri` from here
    // changes that signature and causes NoSuchMethodError at runtime in every extension that
    // builds a Page with default args (kemono/coomer and ~all others). Do NOT move it out again.
    @Transient var uri: Uri? = null,
) : ProgressListener {

    val number: Int
        get() = index + 1

    @Transient
    private val _statusFlow = MutableStateFlow(State.QUEUE)

    @Transient
    val statusFlow = _statusFlow.asStateFlow()
    var status: State
        get() = _statusFlow.value
        set(value) {
            _statusFlow.value = value
        }

    @Transient
    private val _progressFlow = MutableStateFlow(0)

    @Transient
    val progressFlow = _progressFlow.asStateFlow()
    var progress: Int
        get() = _progressFlow.value
        set(value) {
            _progressFlow.value = value
        }

    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
        progress = if (contentLength > 0) {
            (100 * bytesRead / contentLength).toInt()
        } else {
            -1
        }
    }

    enum class State {
        QUEUE,
        LOAD_PAGE,
        DOWNLOAD_IMAGE,
        READY,
        ERROR,
    }
}
