package eu.kanade.tachiyomi.data.torrentServer.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR

/**
 * Foreground [Service] that hosts the native Go torrent server (torrServer).
 *
 * On [ACTION_START] it promotes itself to the foreground with an ongoing notification, then starts
 * the native server off the main thread, waits until the REST endpoint responds, and pushes the
 * tracker list. On [ACTION_STOP] it shuts the native server and REST client down and stops itself.
 */
class TorrentServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("ReturnCount")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                startServer()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopServer()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("MagicNumber")
    private fun startServer() {
        serviceScope.launch {
            try {
                if (TorrentServerApi.echo() == "") {
                    logcat(LogPriority.INFO) {
                        "[Torrent] starting native server, data dir=${applicationContext.filesDir.absolutePath}"
                    }
                    torrServer.TorrServer.startTorrentServer(applicationContext.filesDir.absolutePath)
                    if (wait(10)) {
                        TorrentServerUtils.setTrackersList()
                        isRunning = true
                        logcat(LogPriority.INFO) { "[Torrent] native server is up (${TorrentServerUtils.hostUrl})" }
                    } else {
                        logcat(LogPriority.ERROR) { "[Torrent] native server did not respond within 10s" }
                    }
                } else {
                    isRunning = true
                    logcat(LogPriority.INFO) {
                        "[Torrent] native server already running (${TorrentServerUtils.hostUrl})"
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "[Torrent] unable to start torrent server" }
            }
        }
    }

    private fun stopServer() {
        serviceScope.launch {
            try {
                logcat(LogPriority.INFO) { "[Torrent] stopping native server" }
                torrServer.TorrServer.stopTorrentServer()
                TorrentServerApi.shutdown()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "[Torrent] unable to stop torrent server" }
            } finally {
                isRunning = false
                applicationContext.cancelNotification(Notifications.ID_TORRENT_SERVER)
                stopSelf()
                logcat(LogPriority.INFO) { "[Torrent] server service stopped" }
            }
        }
    }

    private fun startForegroundNotification() {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TorrentServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = notificationBuilder(Notifications.CHANNEL_TORRENT_SERVER) {
            setSmallIcon(R.mipmap.ic_launcher)
            setContentTitle(stringResource(MR.strings.app_name))
            setContentText(stringResource(AYMR.strings.torrent_server_is_running))
            setAutoCancel(false)
            setOngoing(true)
            setUsesChronometer(true)
            addAction(
                R.drawable.ic_close_24dp,
                stringResource(AYMR.strings.action_stop_torrent_server),
                stopIntent,
            )
        }.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_TORRENT_SERVER,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.ID_TORRENT_SERVER, notification)
        }
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start_torrent_server"
        const val ACTION_STOP = "stop_torrent_server"

        @Volatile
        var isRunning = false
            private set

        @Suppress("TooGenericExceptionCaught")
        fun start(context: Context) {
            try {
                val intent = Intent(context, TorrentServerService::class.java).apply {
                    action = ACTION_START
                }
                context.startService(intent)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Unable to start TorrentServerService" }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun stop(context: Context) {
            try {
                val intent = Intent(context, TorrentServerService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Unable to stop TorrentServerService" }
            }
        }

        /**
         * Blocks until the torrent server REST endpoint responds or [timeout] seconds elapse.
         *
         * @return true if the server responded within the timeout, false otherwise.
         */
        @Suppress("MagicNumber")
        fun wait(timeout: Int = -1): Boolean {
            var count = 0
            while (TorrentServerApi.echo() == "") {
                Thread.sleep(1000)
                count++
                if (timeout in 0 until count) {
                    return false
                }
            }
            return true
        }
    }
}
