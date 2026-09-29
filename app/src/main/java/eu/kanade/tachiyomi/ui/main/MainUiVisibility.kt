package eu.kanade.tachiyomi.ui.main

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Whether [MainActivity], which hosts the library, updates and history screens, is on screen.
 *
 * Those screens keep their screen models, and the database flows they collect, alive for as long
 * as they are in the back stack: also while the reader or player covers them and while the app is
 * in the background. Every page-progress write from the reader, episode-progress write from the
 * player or chapter insert from a library update then re-ran their aggregate queries over the whole
 * library for a UI nobody could see. [whileMainUiVisible] pauses such a flow until the activity is
 * visible again; the screen keeps showing its last state and catches up with a single query.
 */
object MainUiVisibility {

    private val startedActivities = MutableStateFlow(0)

    val isVisible: Flow<Boolean> = startedActivities
        .map { it > 0 }
        .distinctUntilChanged()

    val lifecycleObserver: DefaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            startedActivities.update { it + 1 }
        }

        override fun onStop(owner: LifecycleOwner) {
            startedActivities.update { (it - 1).coerceAtLeast(0) }
        }
    }
}

/**
 * Collects this flow only while [MainActivity] is visible; see [MainUiVisibility].
 */
fun <T> Flow<T>.whileMainUiVisible(): Flow<T> {
    return MainUiVisibility.isVisible.flatMapLatest { visible -> if (visible) this else emptyFlow() }
}
