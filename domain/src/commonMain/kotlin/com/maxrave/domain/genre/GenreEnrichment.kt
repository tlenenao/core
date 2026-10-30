package com.maxrave.domain.genre

import com.maxrave.domain.data.entities.genre.GenreLookupEntity
import com.maxrave.domain.data.entities.genre.GenreLookupEntity.ProviderState
import com.maxrave.domain.repository.GenreRepository
import com.maxrave.logger.Logger

/**
 * Finds the genres of one favourite song and files it into the matching automatic playlists.
 *
 * Idempotent: running it twice leaves the same state. A provider that already answered
 * (tags, or "nothing found") is never asked again; one that failed is retried with backoff up to
 * [GenreBackoff.MAX_ATTEMPTS] times. Whatever is known is merged and filed after every run, so a
 * provider that succeeded is not held back by one that failed.
 */
class GenreEnrichment(
    private val providers: List<GenreTagProvider>,
    private val repository: GenreRepository,
    private val playlistTitle: (label: String) -> String = { "Genre · $it" },
    private val now: () -> Long,
    private val random: () -> Double,
) {
    sealed interface Outcome {
        /** Every provider settled. */
        data class Done(
            val genres: List<MergedGenre>,
        ) : Outcome

        /** At least one provider must be retried at [nextAttemptAt]; partial results were filed. */
        data class Retry(
            val genres: List<MergedGenre>,
            val nextAttemptAt: Long,
        ) : Outcome

        /** Retries exhausted. Partial results were filed. */
        data class GaveUp(
            val genres: List<MergedGenre>,
        ) : Outcome

        /** The song is not in the local database. Nothing was changed. */
        data object UnknownSong : Outcome
    }

    /** [force] discards what providers answered before and asks everyone again. */
    suspend fun process(
        videoId: String,
        force: Boolean = false,
    ): Outcome {
        val query = repository.getTrackQuery(videoId) ?: return Outcome.UnknownSong
        // Every liked song goes in the "Liked" playlist, whatever the providers find.
        repository.addToGenrePlaylists(videoId, listOf(LIKED), { LIKED_TITLE })
        var lookup = repository.getLookup(videoId)?.takeUnless { force } ?: GenreLookupEntity(videoId)

        var failure = false
        for (provider in providers) {
            if (ProviderState.isSettled(lookup.stateOf(provider.source))) continue
            val result = provider.fetch(query)
            val state =
                when (result) {
                    is ProviderResult.Tags -> {
                        val tags =
                            GenreNormalizer.normalizeAll(
                                provider.source,
                                result.tags,
                                exclude = listOfNotNull(query.artist, query.title, TrackTextCleaner.cleanTitle(query.title)),
                            )
                        // Tags that were all filtered out are a settled "nothing found", not an error.
                        repository.replaceTags(videoId, provider.source, tags, now())
                        if (tags.isEmpty()) ProviderState.NO_RESULT else ProviderState.OK
                    }
                    ProviderResult.NoMatch -> ProviderState.NO_RESULT
                    ProviderResult.Unavailable -> ProviderState.SKIPPED
                    is ProviderResult.TemporaryError -> {
                        failure = true
                        ProviderState.TEMPORARY_ERROR
                    }
                }
            lookup = lookup.withState(provider.source, state)
            Logger.i(TAG, "$videoId ${provider.source}: ${result::class.simpleName} -> state $state")
        }

        val genres = GenreMerger.merge(repository.getTags(videoId).map { it.toNormalized() })
        Logger.i(TAG, "$videoId \"${query.title}\" / ${query.artist}: ${genres.map { it.label }}")
        if (genres.isNotEmpty()) repository.addToGenrePlaylists(videoId, genres, playlistTitle)

        return if (!failure) {
            repository.saveLookup(lookup.copy(finished = true, nextAttemptAt = 0, updatedAt = now()))
            Outcome.Done(genres)
        } else {
            scheduleRetry(lookup, genres)
        }
    }

    /**
     * Counts an attempt that blew up unexpectedly (a bug or a database error, not a provider
     * answer) so the same song cannot be picked again immediately, forever.
     */
    suspend fun recordCrash(videoId: String) {
        scheduleRetry(repository.getLookup(videoId) ?: GenreLookupEntity(videoId), emptyList())
    }

    private suspend fun scheduleRetry(
        lookup: GenreLookupEntity,
        genres: List<MergedGenre>,
    ): Outcome {
        val time = now()
        val attempts = lookup.attempts + 1
        return if (attempts >= GenreBackoff.MAX_ATTEMPTS) {
            repository.saveLookup(lookup.copy(attempts = attempts, finished = true, nextAttemptAt = 0, updatedAt = time))
            Outcome.GaveUp(genres)
        } else {
            val next = time + GenreBackoff.delayMillis(attempts, random())
            repository.saveLookup(lookup.copy(attempts = attempts, finished = false, nextAttemptAt = next, updatedAt = time))
            Outcome.Retry(genres, next)
        }
    }

    private companion object {
        const val TAG = "GenreEnrichment"
        const val LIKED_TITLE = "Liked"
        val LIKED = MergedGenre(key = "__liked__", label = LIKED_TITLE, score = 1.0, sources = emptySet())
    }

    /** Registers a newly favourited song so that it survives a restart. Never overwrites progress. */
    suspend fun register(videoId: String) {
        if (repository.getLookup(videoId) == null) {
            repository.saveLookup(GenreLookupEntity(videoId, updatedAt = now()))
        }
    }

    /** Songs due for another try, one at a time, or null. */
    suspend fun nextDue(): String? = repository.nextDue(now())

    suspend fun earliestPending(): Long? = repository.earliestPending()

    suspend fun reopenGaveUp(): Int = repository.reopenGaveUp(now() - GenreBackoff.GAVE_UP_RETRY_AFTER_MILLIS)
}

internal fun GenreLookupEntity.stateOf(source: GenreSource): Int =
    when (source) {
        GenreSource.LASTFM -> lastfmState
        GenreSource.MUSICBRAINZ -> musicbrainzState
    }

internal fun GenreLookupEntity.withState(
    source: GenreSource,
    state: Int,
): GenreLookupEntity =
    when (source) {
        GenreSource.LASTFM -> copy(lastfmState = state)
        GenreSource.MUSICBRAINZ -> copy(musicbrainzState = state)
    }

internal fun com.maxrave.domain.data.entities.genre.SongGenreTagEntity.toNormalized() =
    NormalizedTag(genreKey, label, GenreSource.valueOf(source), weight)
