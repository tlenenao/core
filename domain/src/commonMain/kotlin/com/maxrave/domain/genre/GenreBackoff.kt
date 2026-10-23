package com.maxrave.domain.genre

import kotlin.math.min
import kotlin.math.pow

/** Retry schedule for temporary provider failures: exponential, capped, with jitter. */
object GenreBackoff {
    const val MAX_ATTEMPTS = 6
    private const val BASE_MILLIS = 60_000L
    private const val CAP_MILLIS = 6 * 60 * 60 * 1000L

    /** A song that gave up is offered another chance after this long (see `GenreEnrichment.resume`). */
    const val GAVE_UP_RETRY_AFTER_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * Delay before attempt number [attempt]+1, where [attempt] is how many have failed so far (>= 1).
     * [random] is in 0.0..1.0 and spreads the delay over +-25% so many songs failing together do
     * not all come back in the same second.
     */
    fun delayMillis(
        attempt: Int,
        random: Double,
    ): Long {
        val exponential = min(CAP_MILLIS.toDouble(), BASE_MILLIS * 2.0.pow((attempt - 1).coerceAtLeast(0)))
        return (exponential * (0.75 + 0.5 * random.coerceIn(0.0, 1.0))).toLong()
    }
}
