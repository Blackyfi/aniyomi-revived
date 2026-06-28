package eu.kanade.tachiyomi.data.headless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Non-exported, root/adb-only headless test harness.
 *
 * Exercises installed source extensions through their real suspend API (the same calls the app
 * makes) without any UI, so per-extension health can be probed deterministically with no mistaps.
 *
 * Invoke (requires `adb root` since the component is not exported):
 *   adb shell am start-foreground-service \
 *     -n com.blackyfi.aniyomirevived/eu.kanade.tachiyomi.data.headless.HeadlessTestService \
 *     -a eu.kanade.tachiyomi.HEADLESS_TEST \
 *     --es scope all --ei depth 4 --el timeout 30000 [--es source <idOrNameSubstring>]
 *
 * Output: a JSON report at getExternalFilesDir(null)/headless-report.json plus one summary line
 * per source on logcat tag "HeadlessTest".
 */
class HeadlessTestService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        val scopeArg = intent?.getStringExtra("scope") ?: "all"
        val depth = intent?.getIntExtra("depth", 4) ?: 4
        val timeout = intent?.getLongExtra("timeout", 30_000L) ?: 30_000L
        val filter = intent?.getStringExtra("source")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        scope.launch {
            val report = JSONArray()
            try {
                if (scopeArg == "all" || scopeArg == "anime") {
                    runCatching { Injekt.get<AnimeSourceManager>().getCatalogueSources() }
                        .getOrDefault(emptyList())
                        .filter { matches(it.id, it.name, filter) }
                        .forEach { report.put(testAnime(it, depth, timeout)) }
                }
                if (scopeArg == "all" || scopeArg == "manga") {
                    runCatching { Injekt.get<MangaSourceManager>().getCatalogueSources() }
                        .getOrDefault(emptyList())
                        .filter { matches(it.id, it.name, filter) }
                        .forEach { report.put(testManga(it, depth, timeout)) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "harness crashed", t)
            } finally {
                writeReport(report)
                Log.i(TAG, "DONE total=${report.length()}")
                stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    private fun matches(id: Long, name: String, filter: String?): Boolean {
        if (filter == null) return true
        return id.toString() == filter || name.lowercase().contains(filter)
    }

    private suspend fun testAnime(source: AnimeCatalogueSource, depth: Int, timeout: Long): JSONObject {
        val o = baseObject(source.id, source.name, source.lang, "anime")
        val start = System.currentTimeMillis()
        val popular = step(o, "popular") { withTimeout(timeout) { source.getPopularAnime(1).animes } }
        if (depth >= 2 && !popular.isNullOrEmpty()) {
            val first = popular.first()
            step(o, "details") { withTimeout(timeout) { source.getAnimeDetails(first) } }
            if (depth >= 3) {
                val episodes = step(o, "list") { withTimeout(timeout) { source.getEpisodeList(first) } }
                if (depth >= 4 && !episodes.isNullOrEmpty()) {
                    step(o, "content") { withTimeout(timeout) { source.getVideoList(episodes.first()) } }
                }
            }
        }
        o.put("totalMs", System.currentTimeMillis() - start)
        Log.i(TAG, summary(o))
        return o
    }

    private suspend fun testManga(source: CatalogueSource, depth: Int, timeout: Long): JSONObject {
        val o = baseObject(source.id, source.name, source.lang, "manga")
        val start = System.currentTimeMillis()
        val popular = step(o, "popular") { withTimeout(timeout) { source.getPopularManga(1).mangas } }
        if (depth >= 2 && !popular.isNullOrEmpty()) {
            val first = popular.first()
            step(o, "details") { withTimeout(timeout) { source.getMangaDetails(first) } }
            if (depth >= 3) {
                val chapters = step(o, "list") { withTimeout(timeout) { source.getChapterList(first) } }
                if (depth >= 4 && !chapters.isNullOrEmpty()) {
                    step(o, "content") { withTimeout(timeout) { source.getPageList(chapters.first()) } }
                }
            }
        }
        o.put("totalMs", System.currentTimeMillis() - start)
        Log.i(TAG, summary(o))
        return o
    }

    /**
     * Run one step, record {ok,count,error} into [o] under [key], return the result (or null on
     * failure). [count] is the collection size for list results, or 1 for a single object.
     */
    private suspend fun <T> step(o: JSONObject, key: String, block: suspend () -> T): T? {
        val r = JSONObject()
        return try {
            val result = block()
            r.put("ok", true).put("count", (result as? Collection<*>)?.size ?: 1)
            o.put(key, r)
            result
        } catch (t: Throwable) {
            r.put("ok", false).put("error", "${t.javaClass.simpleName}: ${t.message?.take(300)}")
            o.put(key, r)
            null
        }
    }

    private fun baseObject(id: Long, name: String, lang: String, type: String) = JSONObject()
        .put("id", id).put("name", name).put("lang", lang).put("type", type)

    private fun summary(o: JSONObject): String {
        fun s(k: String) = o.optJSONObject(k)?.let {
            if (it.optBoolean("ok")) "${it.optInt("count")}" else "ERR(${it.optString("error").take(60)})"
        } ?: "-"
        return "[${o.optString("type")}] ${o.optString("name")} | pop=${s("popular")} det=${s("details")} " +
            "list=${s("list")} content=${s("content")} ${o.optLong("totalMs")}ms"
    }

    private fun writeReport(report: JSONArray) {
        runCatching {
            val out = File(getExternalFilesDir(null), "headless-report.json")
            out.writeText(report.toString(2))
            Log.i(TAG, "report -> ${out.absolutePath}")
        }.onFailure { Log.e(TAG, "write failed", it) }
    }

    private fun startForegroundCompat() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Headless Test", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Running headless extension test")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HeadlessTest"
        private const val CHANNEL = "headless_test"
        private const val NOTIF_ID = 0x48454144 // "HEAD"
    }
}
