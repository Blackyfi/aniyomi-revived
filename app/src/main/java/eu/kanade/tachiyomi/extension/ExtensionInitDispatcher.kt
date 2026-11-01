package eu.kanade.tachiyomi.extension

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher

/**
 * Where extension source constructors run.
 *
 * Extensions are third-party code that has only ever been constructed one at a time on a thread with
 * a Looper, namely the main one. This thread keeps those two guarantees without holding up the UI, so
 * a constructor that creates a default Handler or touches unsynchronized state of its own still works.
 */
internal val extensionInitDispatcher: CoroutineDispatcher by lazy {
    val thread = HandlerThread("ExtensionInit").apply { start() }
    Handler(thread.looper).asCoroutineDispatcher("ExtensionInit")
}
