package eu.kanade.presentation.util

import android.content.Context
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.system.isOnline
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.items.chapter.model.NoChaptersException
import tachiyomi.domain.items.episode.model.NoEpisodesException
import tachiyomi.domain.source.anime.model.AnimeSourceNotInstalledException
import tachiyomi.domain.source.manga.model.SourceNotInstalledException
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

context(Context)
val Throwable.formattedMessage: String
    get() {
        when (this) {
            is HttpException -> return when (code) {
                429 -> stringResource(AYMR.strings.exception_http_too_many_requests)
                in 500..599 -> stringResource(AYMR.strings.exception_http_server_error, code)
                else -> stringResource(MR.strings.exception_http, code)
            }
            is SocketTimeoutException -> return if (!isOnline()) {
                stringResource(MR.strings.exception_offline)
            } else {
                stringResource(AYMR.strings.exception_timeout)
            }
            is ConnectException, is NoRouteToHostException -> return if (!isOnline()) {
                stringResource(MR.strings.exception_offline)
            } else {
                stringResource(AYMR.strings.exception_connect)
            }
            is SSLException -> return stringResource(AYMR.strings.exception_ssl)
            is UnknownHostException -> {
                return if (!isOnline()) {
                    stringResource(MR.strings.exception_offline)
                } else {
                    stringResource(MR.strings.exception_unknown_host, message ?: "")
                }
            }
            is NoChaptersException, is NoEpisodesException -> return stringResource(
                MR.strings.no_results_found,
            )
            is SourceNotInstalledException, is AnimeSourceNotInstalledException -> return stringResource(
                MR.strings.loader_not_implemented_error,
            )
        }
        return when (val className = this::class.simpleName) {
            "Exception", "IOException" -> message ?: className
            else -> "$className: $message"
        }
    }
