package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.genre.GenreLookupEntity
import com.maxrave.domain.data.entities.genre.SongGenreTagEntity
import com.maxrave.domain.genre.GenreSource
import com.maxrave.domain.genre.MergedGenre
import com.maxrave.domain.genre.NormalizedTag
import com.maxrave.domain.genre.TrackQuery

/** Storage the genre feature needs. Kept separate from [SongRepository] so that one stays small. */
interface GenreRepository {
    /** Metadata to search with, or null when the song no longer exists locally. */
    suspend fun getTrackQuery(videoId: String): TrackQuery?

    suspend fun getLookup(videoId: String): GenreLookupEntity?

    suspend fun saveLookup(lookup: GenreLookupEntity)

    /** Replaces what [source] previously stored for the song. Other sources are untouched. */
    suspend fun replaceTags(
        videoId: String,
        source: GenreSource,
        tags: List<NormalizedTag>,
        fetchedAt: Long,
    )

    suspend fun getTags(videoId: String): List<SongGenreTagEntity>

    /**
     * Makes sure each genre has an automatic playlist and that the song is in it, atomically.
     * Only adds: nothing is ever removed from a playlist. Safe to call any number of times.
     * @return how many playlist memberships were newly created.
     */
    suspend fun addToGenrePlaylists(
        videoId: String,
        genres: List<MergedGenre>,
        playlistTitle: (label: String) -> String,
    ): Int

    /** A song whose retry time has come, or null. Songs that are finished are never returned. */
    suspend fun nextDue(now: Long): String?

    /** Earliest retry time among unfinished songs, or null when nothing is waiting. */
    suspend fun earliestPending(): Long?

    /** Gives songs that gave up long ago a fresh set of attempts. */
    suspend fun reopenGaveUp(olderThan: Long): Int
}
