package tachiyomi.presentation.widget.util

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Lets the widget managers watch the database only while one of their widgets is on a home screen.
 *
 * The managers start with the process, and the updates query they observe re-runs on every chapter
 * or episode write (each reader page turn, each player progress save, each library-update insert).
 * Without a placed widget all of that work was thrown away.
 */
internal object WidgetPresence {

    // Bumped by the widget receivers when the first widget of a kind is added or the last removed.
    private val changes = MutableStateFlow(0)

    fun notifyChanged() {
        changes.update { it + 1 }
    }

    /**
     * Emits [flow] while at least one widget of [receivers] is placed, and nothing otherwise.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> Context.whileAnyPlaced(vararg receivers: Class<*>, flow: Flow<T>): Flow<T> {
        return changes
            .map { hasAnyWidget(receivers) }
            .distinctUntilChanged()
            .flatMapLatest { placed -> if (placed) flow else emptyFlow() }
    }

    private fun Context.hasAnyWidget(receivers: Array<out Class<*>>): Boolean {
        return try {
            val manager = AppWidgetManager.getInstance(this) ?: return false
            receivers.any { manager.getAppWidgetIds(ComponentName(this, it)).isNotEmpty() }
        } catch (_: Exception) {
            // Can't tell; keep the widgets updating as before.
            true
        }
    }
}
