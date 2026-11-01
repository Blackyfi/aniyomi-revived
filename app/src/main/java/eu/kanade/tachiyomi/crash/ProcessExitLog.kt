package eu.kanade.tachiyomi.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.time.Instant

/**
 * Copies recent abnormal process deaths reported by the system into the local log, so ANRs and
 * native crashes show up on the in-app debug logs screen. Nothing leaves the device unless the
 * user shares the log.
 */
object ProcessExitLog {

    /** How many exit records to ask the system for. It keeps a limited history per package. */
    private const val MAX_RECORDS = 16

    /** How many abnormal exits to log. The log buffer is shared, so this stays small. */
    private const val MAX_LOGGED = 5

    private const val MAX_STACK_FRAMES = 30

    /** Stops the search in a trace that has no main thread section. */
    private const val MAX_TRACE_LINES = 5_000

    private const val MAIN_THREAD_HEADER = "\"main\" "

    fun record(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val activityManager = context.getSystemService<ActivityManager>() ?: return
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
                .filter { isAbnormal(it.reason) }
                .take(MAX_LOGGED)
                // The system returns newest first; log oldest first, as they happened.
                .asReversed()
                .forEach { info -> logcat(LogPriority.WARN) { describe(info) } }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not read process exit reasons" }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(info: ApplicationExitInfo): String = buildString {
        append("Previous exit: ").append(reasonName(info.reason))
        append(" at ").append(Instant.ofEpochMilli(info.timestamp))
        append(", process ").append(info.processName)
        append(", importance ").append(info.importance)
        append(", pss ").append(info.pss / 1024).append(" MB")
        append(", rss ").append(info.rss / 1024).append(" MB")
        info.description?.let { append("\n").append(it) }
        if (info.reason == ApplicationExitInfo.REASON_ANR) {
            mainThreadStackOf(info)?.let { append("\nMain thread:\n").append(it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun mainThreadStackOf(info: ApplicationExitInfo): String? {
        return try {
            info.traceInputStream?.bufferedReader()?.useLines { mainThreadStack(it) }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not read the ANR trace" }
            null
        }
    }

    /** Exits worth investigating. User actions, updates and normal exits are left out. */
    internal fun isAbnormal(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_FREEZER,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        -> true
        else -> false
    }

    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_SIGNALED -> "signal"
        else -> "reason $reason"
    }

    /**
     * Returns the stack frames of the main thread from an ANR trace, or null if the trace has no
     * main thread section. Frames are class and method names, so no user data is copied.
     */
    internal fun mainThreadStack(lines: Sequence<String>): String? {
        val section = lines.take(MAX_TRACE_LINES)
            .dropWhile { !it.startsWith(MAIN_THREAD_HEADER) }
            .drop(1)
            .takeWhile { it.isNotBlank() }
        val frames = section.map { it.trim() }
            .filter { it.startsWith("at ") || it.startsWith("native: ") }
            .take(MAX_STACK_FRAMES)
            .toList()
        return frames.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
