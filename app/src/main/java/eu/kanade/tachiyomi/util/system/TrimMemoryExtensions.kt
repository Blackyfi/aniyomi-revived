package eu.kanade.tachiyomi.util.system

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration

/**
 * Runs [block] on the main thread whenever the process is in the background and the system asks
 * it to give memory back. Meant for app-lifetime singletons, as the callback is never unregistered.
 */
fun Context.doOnTrimMemoryBackground(block: () -> Unit) {
    registerComponentCallbacks(
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) block()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        },
    )
}
