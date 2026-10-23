package com.maxrave.domain.genre

/** Re-runs the genre lookup for every liked song, asking the providers again. */
interface GenreReingest {
    /** Queues the work and returns at once; the result is how many songs were queued. */
    suspend fun reingestLikedSongs(): Int
}
