package eu.kanade.tachiyomi.ui.library

/**
 * Items between the entries [fromKey] and [toKey] (both included), looked up by key so that an
 * entry changed by a running update still matches.
 *
 * If [toKey] is no longer listed there is nothing to add. If only [fromKey] is gone, for example
 * because a filter hid it, the selection falls back to just the tapped entry.
 */
internal fun <T, K> itemsBetween(items: List<T>, key: (T) -> K, fromKey: K, toKey: K): List<T> {
    val toIndex = items.indexOfFirst { key(it) == toKey }
    if (toIndex < 0) return emptyList()
    val fromIndex = items.indexOfFirst { key(it) == fromKey }
    return when {
        fromIndex < 0 -> listOf(items[toIndex])
        fromIndex <= toIndex -> items.subList(fromIndex, toIndex + 1)
        else -> items.subList(toIndex, fromIndex + 1)
    }
}

/**
 * Mean of the available tracker scores, or null when no tracker could score the entry, such as
 * when it is only tracked on services the user has logged out of.
 */
internal fun meanTrackerScore(scores: List<Double>): Double? {
    return if (scores.isEmpty()) null else scores.average()
}
