package com.maxrave.data.genre

import com.maxrave.domain.genre.GenreEnrichment
import com.maxrave.domain.genre.GenreReingest
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "GenreQueue"

/**
 * Runs [GenreEnrichment] in the background, one song at a time.
 *
 * Nothing here is awaited by the caller: [onSongLiked] returns immediately and every failure stays
 * inside this class, so liking a song can never wait on, or fail because of, a network call.
 *
 * A single drain loop runs at a time. It stops by itself once nothing is due and the next retry is
 * more than [MAX_IDLE_WAIT_MILLIS] away (retries are persisted, so [resume] — called at startup,
 * and again by any new favourite — picks them up). Retries are bounded by
 * `GenreBackoff.MAX_ATTEMPTS`, so the loop cannot spin forever.
 */
@OptIn(ExperimentalTime::class)
internal class GenreEnrichmentQueue(
    private val enrichment: GenreEnrichment,
    private val likedSongIds: suspend () -> List<String> = { emptyList() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : GenreReingest {
    private val lock = Mutex()

    /** Songs favourited this session that must be (re)filed even if already finished. */
    private val requested = ArrayDeque<String>()
    private var drain: Job? = null

    /** Songs whose providers must all be asked again, whatever they answered before. */
    private val forced = HashSet<String>()

    /** The Settings button: every liked song is filed again, with fresh answers from the providers. */
    override suspend fun reingestLikedSongs(): Int {
        val ids = likedSongIds()
        lock.withLock {
            for (id in ids) {
                forced += id
                if (id !in requested) requested.addLast(id)
            }
        }
        if (ids.isNotEmpty()) ensureDraining()
        return ids.size
    }

    fun onSongLiked(videoId: String) {
        scope.launch {
            try {
                // Persisted first, so a favourite survives the app being killed before the drain runs.
                enrichment.register(videoId)
                lock.withLock { if (videoId !in requested) requested.addLast(videoId) }
                ensureDraining()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Could not queue $videoId: ${e.message}")
            }
        }
    }

    /** Picks up songs left unfinished by a previous session. Delayed so it never competes with startup. */
    fun resume() {
        scope.launch {
            delay(STARTUP_DELAY_MILLIS)
            try {
                enrichment.reopenGaveUp()
                ensureDraining()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Resume failed: ${e.message}")
            }
        }
    }

    private suspend fun ensureDraining() {
        lock.withLock {
            if (drain != null) return
            drain = scope.launch { drainLoop() }
        }
    }

    private suspend fun drainLoop() {
        while (true) {
            val videoId = lock.withLock { requested.removeFirstOrNull() } ?: enrichment.nextDue()
            if (videoId == null) {
                val next = enrichment.earliestPending()
                val wait = if (next == null) null else next - Clock.System.now().toEpochMilliseconds()
                if (wait == null || wait > MAX_IDLE_WAIT_MILLIS) {
                    // Decided under the lock: a song queued a moment ago is either seen here or
                    // finds `drain == null` and starts a new loop. It cannot fall in between.
                    lock.withLock {
                        if (requested.isEmpty()) {
                            drain = null
                            return
                        }
                    }
                } else {
                    delay(wait.coerceAtLeast(1_000L))
                }
                continue
            }
            try {
                Logger.d(TAG, "Processing genres for $videoId")
                enrichment.process(videoId, force = lock.withLock { forced.remove(videoId) })
            } catch (e: CancellationException) {
                lock.withLock { drain = null }
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Genre processing failed for $videoId: ${e::class.simpleName}")
                runCatching { enrichment.recordCrash(videoId) }
            }
        }
    }

    companion object {
        const val STARTUP_DELAY_MILLIS = 20_000L
        const val MAX_IDLE_WAIT_MILLIS = 15 * 60 * 1000L
    }
}
