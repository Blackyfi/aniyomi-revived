package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.networkStateFlow
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * This worker is used to manage the downloader. The system can decide to stop the worker, in
 * which case the downloader is also stopped. It's also stopped while there's no network available,
 * and started again by the system once there is.
 */
class MangaDownloadJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val downloadManager: MangaDownloadManager = Injekt.get()
    private val downloadPreferences: DownloadPreferences = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(applicationContext.getString(R.string.download_notifier_downloader_title))
            setSmallIcon(android.R.drawable.stat_sys_download)
        }.build()
        return ForegroundInfo(
            Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        // A resumed run can be the first thing a new process does, with the queue not back yet.
        withTimeoutOrNull(QUEUE_RESTORE_TIMEOUT) { downloadManager.awaitQueueRestored() }

        val requireWifi = downloadPreferences.downloadOnlyOverWifi()
        if (!checkNetworkState(applicationContext.activeNetworkState(), requireWifi.get())) {
            val attempt = inputData.getInt(KEY_RESUME_ATTEMPT, 0) + 1
            if (attempt <= MAX_RESUME_ATTEMPTS && downloadManager.queueState.value.isNotEmpty()) {
                resumeWhenOnline(applicationContext, requireWifi.get(), attempt)
            }
            return Result.failure()
        }

        // The system can stop this worker and rerun it while the downloader keeps going.
        if (!downloadManager.isRunning && !downloadManager.downloaderStart()) {
            return Result.failure()
        }

        setForegroundSafely()

        // WorkManager holds a wake lock for as long as a foreground worker runs, so don't outlive
        // the connection. Waits here until connectivity no longer allows downloading; when the queue
        // finishes or the user pauses it, the downloader cancels this worker instead. The short
        // debounce lets a network handover settle rather than interrupting the downloads; capability
        // callbacks also fire for signal changes, which must not keep restarting it.
        val (_, wifiRequired) = combine(
            applicationContext.networkStateFlow(),
            requireWifi.changes(),
        ) { state, wifi -> state to wifi }
            .distinctUntilChanged()
            .debounce(NETWORK_SETTLE_TIME)
            .first { (state, wifi) -> !checkNetworkState(state, wifi) }

        resumeWhenOnline(applicationContext, wifiRequired, attempt = 0)
        return Result.success()
    }

    private fun checkNetworkState(state: NetworkState, requireWifi: Boolean): Boolean {
        return if (state.isOnline) {
            val noWifi = requireWifi && !state.isWifi
            if (noWifi) {
                downloadManager.downloaderStop(
                    applicationContext.getString(R.string.download_notifier_text_only_wifi),
                )
            }
            !noWifi
        } else {
            downloadManager.downloaderStop(applicationContext.getString(R.string.download_notifier_no_network))
            false
        }
    }

    companion object {
        private const val TAG = "MangaDownloader"
        private const val KEY_RESUME_ATTEMPT = "resumeAttempt"
        private const val MAX_RESUME_ATTEMPTS = 5
        private val RESUME_DELAY = 30.seconds
        private val NETWORK_SETTLE_TIME = 3.seconds
        private val QUEUE_RESTORE_TIMEOUT = 30.seconds

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<MangaDownloadJob>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(TAG)
        }

        /**
         * Lets the system start the downloader again once the required network is back, so nothing
         * has to stay awake waiting for it. Replaces the current run; [stop] cancels it. [attempt]
         * counts runs that found no usable network although the constraint was met (e.g. the two
         * disagree about a captive portal), so they back off and eventually give up.
         */
        private fun resumeWhenOnline(context: Context, requireWifi: Boolean, attempt: Int) {
            val networkRequest = NetworkRequest.Builder().apply {
                if (requireWifi) addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            }.build()
            val constraints = Constraints.Builder()
                // 'networkRequest' only applies to Android 9+, otherwise 'networkType' is used
                .setRequiredNetworkRequest(
                    networkRequest,
                    if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()
            val request = OneTimeWorkRequestBuilder<MangaDownloadJob>()
                .addTag(TAG)
                .setConstraints(constraints)
                .setInitialDelay((RESUME_DELAY * (attempt + 1)).toJavaDuration())
                .setInputData(workDataOf(KEY_RESUME_ATTEMPT to attempt))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun isRunning(context: Context): Boolean {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(TAG)
                .get()
                .let { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }
    }
}
