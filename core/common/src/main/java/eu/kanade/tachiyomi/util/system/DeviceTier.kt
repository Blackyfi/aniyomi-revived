package eu.kanade.tachiyomi.util.system

/**
 * Coarse bucket of a device's total RAM, for settings where memory is the real constraint.
 */
enum class DeviceTier {
    LOW,
    AVERAGE,
    HIGH,
    ;

    companion object {
        private const val GIB = 1024L * 1024 * 1024

        /**
         * Below this is [LOW]. Matches what the app has always treated as a low memory device.
         */
        private const val LOW_BELOW_BYTES = 3 * GIB

        /**
         * At or above this is [HIGH]. Total memory excludes what the kernel reserves, so a
         * "6 GB" phone reports noticeably less than 6 GiB.
         */
        private const val HIGH_FROM_BYTES = 5 * GIB

        fun fromTotalMemory(totalBytes: Long): DeviceTier {
            return when {
                totalBytes < LOW_BELOW_BYTES -> LOW
                totalBytes < HIGH_FROM_BYTES -> AVERAGE
                else -> HIGH
            }
        }
    }
}
