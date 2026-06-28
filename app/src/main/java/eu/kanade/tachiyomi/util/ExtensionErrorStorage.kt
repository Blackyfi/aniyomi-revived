package eu.kanade.tachiyomi.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

@Serializable
data class ExtensionErrorEntry(
    val id: String,
    val timeMillis: Long,
    val sourceId: Long,
    val sourceName: String,
    /** Human-readable operation that failed, e.g. "Load episodes". */
    val operation: String,
    val message: String,
    val stackTrace: String,
)

/**
 * Persistent, capped store of the most recent errors thrown by extensions, surfaced in
 * Settings → Advanced → "Extension error log".
 *
 * Unlike [tachiyomi.core.common.util.system.InMemoryLogcatBuffer] this survives process death,
 * so a user can copy a failing extension's full stack trace after the fact. Backed by a single
 * JSON blob in the [PreferenceStore]; the list is capped to [MAX_ENTRIES] (oldest evicted first).
 */
object ExtensionErrorStorage {

    private const val MAX_ENTRIES = 200
    private const val MAX_STACKTRACE_LENGTH = 12_000

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val preference: Preference<List<ExtensionErrorEntry>> by lazy {
        Injekt.get<PreferenceStore>().getObject(
            key = "extension_error_log",
            defaultValue = emptyList(),
            serializer = { json.encodeToString(it) },
            deserializer = {
                runCatching { json.decodeFromString<List<ExtensionErrorEntry>>(it) }
                    .getOrDefault(emptyList())
            },
        )
    }

    private val lock = Any()

    /**
     * Records an extension failure. Never throws — it is safe to call from inside any catch block
     * (a failure here must never mask the original error).
     */
    fun record(sourceId: Long, sourceName: String, operation: String, throwable: Throwable) {
        runCatching {
            val entry = ExtensionErrorEntry(
                id = UUID.randomUUID().toString(),
                timeMillis = System.currentTimeMillis(),
                sourceId = sourceId,
                sourceName = sourceName,
                operation = operation,
                message = throwable.message?.takeIf { it.isNotBlank() }
                    ?: throwable::class.simpleName.orEmpty(),
                stackTrace = throwable.stackTraceToString().take(MAX_STACKTRACE_LENGTH),
            )
            synchronized(lock) {
                preference.set((preference.get() + entry).takeLast(MAX_ENTRIES))
            }
        }
    }

    /** A point-in-time copy, oldest entry first. */
    fun getAll(): List<ExtensionErrorEntry> =
        runCatching { preference.get() }.getOrDefault(emptyList())

    fun delete(id: String) {
        synchronized(lock) {
            preference.set(preference.get().filterNot { it.id == id })
        }
    }

    fun clear() {
        synchronized(lock) { preference.set(emptyList()) }
    }
}
