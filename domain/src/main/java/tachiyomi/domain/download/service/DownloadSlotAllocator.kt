package tachiyomi.domain.download.service

/**
 * Picks which queued downloads should be running right now.
 *
 * [queuesBySource] holds the still-unfinished downloads of a single source each, in queue
 * order, paired with that source's politeness cap: how many of them we would rather not
 * exceed concurrently against that one host. The result never exceeds [slots].
 *
 * It takes two passes, because the two goals conflict:
 *
 *  1. Round-robin while honouring every cap, so a queue spanning several sources spreads
 *     across them instead of letting the first source take every slot.
 *  2. Hand whatever slots nobody claimed to the sources that still have work. A slot left
 *     over after the first pass means no *other* source can use it, and holding it back
 *     would make "Download slots" quietly mean the cap for anyone downloading from a
 *     single site -- which is the normal case, not the exception.
 */
fun <T> allocateDownloadSlots(
    queuesBySource: List<Pair<List<T>, Int>>,
    slots: Int,
): List<T> {
    if (slots <= 0) return emptyList()
    return buildList {
        val taken = IntArray(queuesBySource.size)
        for (honourCaps in booleanArrayOf(true, false)) {
            var progressed = true
            while (size < slots && progressed) {
                progressed = false
                queuesBySource.forEachIndexed { index, (downloads, cap) ->
                    if (size >= slots) return@forEachIndexed
                    val limit = if (honourCaps) minOf(cap, downloads.size) else downloads.size
                    if (taken[index] < limit) {
                        add(downloads[taken[index]])
                        taken[index]++
                        progressed = true
                    }
                }
            }
        }
    }
}
