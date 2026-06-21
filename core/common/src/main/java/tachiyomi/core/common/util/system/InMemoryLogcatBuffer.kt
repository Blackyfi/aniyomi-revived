package tachiyomi.core.common.util.system

import logcat.LogPriority
import logcat.LogcatLogger

/**
 * An always-on, in-memory [LogcatLogger] that keeps the most recent log entries in
 * a bounded ring buffer so they can be inspected from inside the app (see the
 * in-app "Debug logs" screen).
 *
 * This is installed unconditionally at startup — independently of the
 * "verbose logging" preference — so that errors which are otherwise silently
 * swallowed (caught + logged at [LogPriority.ERROR] without a logger installed)
 * are still captured and available for debugging without `adb`.
 */
object InMemoryLogcatBuffer : LogcatLogger {

    /** Maximum number of entries kept. Older entries are evicted first. */
    private const val MAX_ENTRIES = 500

    /** Per-entry message cap to avoid a single huge log (e.g. a body dump) eating the buffer. */
    private const val MAX_MESSAGE_LENGTH = 4_000

    data class Entry(
        val timeMillis: Long,
        val priority: LogPriority,
        val tag: String,
        val message: String,
    )

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private val lock = Any()

    override fun isLoggable(priority: LogPriority): Boolean = true

    override fun log(priority: LogPriority, tag: String, message: String) {
        val safeMessage = if (message.length > MAX_MESSAGE_LENGTH) {
            message.take(MAX_MESSAGE_LENGTH) + "… (truncated)"
        } else {
            message
        }
        val entry = Entry(
            timeMillis = System.currentTimeMillis(),
            priority = priority,
            tag = tag,
            message = safeMessage,
        )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    /** A point-in-time copy of the buffer, oldest entry first. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }
}

/**
 * Fans a single log call out to several [LogcatLogger]s. The [logcat] library only
 * allows one installed logger, so this lets the in-memory buffer coexist with the
 * Android logcat logger when verbose logging is enabled.
 */
class CompositeLogcatLogger(private val loggers: List<LogcatLogger>) : LogcatLogger {

    override fun isLoggable(priority: LogPriority): Boolean = loggers.any { it.isLoggable(priority) }

    override fun log(priority: LogPriority, tag: String, message: String) {
        loggers.forEach { logger ->
            if (logger.isLoggable(priority)) {
                logger.log(priority, tag, message)
            }
        }
    }
}
