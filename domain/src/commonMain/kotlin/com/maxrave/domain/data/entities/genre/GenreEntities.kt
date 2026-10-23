package com.maxrave.domain.data.entities.genre

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity

/**
 * One tag one provider returned for one song, already normalised.
 *
 * Stored per provider and *before* selection, so the merge can be re-run offline and the
 * provenance of every genre stays visible. [weight] is 0..100 on a per-response scale (Last.fm's
 * own `count`, or MusicBrainz votes relative to the top tag of that response).
 */
@Entity(
    tableName = "song_genre_tag",
    primaryKeys = ["videoId", "genreKey", "source"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["videoId"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SongGenreTagEntity(
    val videoId: String,
    /** Normalised identifier, see `GenreNormalizer`. Never shown to the user. */
    val genreKey: String,
    val label: String,
    /** [com.maxrave.domain.genre.GenreSource.name]. */
    val source: String,
    val weight: Int,
    val fetchedAt: Long,
)

/**
 * Processing state of a song, one row per song.
 *
 * Each provider has its own state so that a failure of one never forces the other to be asked
 * again, and so that "answered, nothing found" can be told apart from "could not answer".
 */
@Entity(
    tableName = "genre_lookup",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["videoId"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GenreLookupEntity(
    @PrimaryKey val videoId: String,
    @ColumnInfo(defaultValue = "0") val lastfmState: Int = ProviderState.PENDING,
    @ColumnInfo(defaultValue = "0") val musicbrainzState: Int = ProviderState.PENDING,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    /** Epoch millis before which the song must not be tried again. */
    @ColumnInfo(defaultValue = "0") val nextAttemptAt: Long = 0,
    /** True once every provider is settled, or retries are exhausted. */
    @ColumnInfo(defaultValue = "0") val finished: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
) {
    object ProviderState {
        /** Not asked yet. */
        const val PENDING = 0

        /** Answered with tags. */
        const val OK = 1

        /** Answered, no usable match. Final: asking again would return the same. */
        const val NO_RESULT = 2

        /** Could not answer (network, rate limit, 5xx). Will be retried. */
        const val TEMPORARY_ERROR = 3

        /** Provider unavailable in this build or configuration. Final. */
        const val SKIPPED = 4

        fun isSettled(state: Int) = state == OK || state == NO_RESULT || state == SKIPPED
    }
}

/**
 * Which local playlist belongs to which genre.
 *
 * This table is what marks a playlist as automatic: a playlist that is not referenced here is
 * never touched by the genre feature. Deleting the playlist cascades to this row, so the next
 * song of that genre simply recreates it.
 */
@Entity(
    tableName = "genre_playlist",
    foreignKeys = [
        ForeignKey(
            entity = LocalPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GenrePlaylistEntity(
    @PrimaryKey val genreKey: String,
    @ColumnInfo(index = true) val playlistId: Long,
)
