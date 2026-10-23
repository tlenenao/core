package com.maxrave.data.genre

import DatabaseDao
import com.maxrave.domain.data.entities.genre.GenreLookupEntity
import com.maxrave.domain.data.entities.genre.SongGenreTagEntity
import com.maxrave.domain.genre.GenreSource
import com.maxrave.domain.genre.MergedGenre
import com.maxrave.domain.genre.NormalizedTag
import com.maxrave.domain.genre.TrackQuery
import com.maxrave.domain.genre.TrackTextCleaner
import com.maxrave.domain.repository.GenreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class GenreRepositoryImpl(
    private val dao: DatabaseDao,
) : GenreRepository {
    override suspend fun getTrackQuery(videoId: String): TrackQuery? =
        withContext(Dispatchers.IO) {
            dao.getSong(videoId)?.let { song ->
                TrackQuery(
                    videoId = song.videoId,
                    title = song.title,
                    artist = TrackTextCleaner.primaryArtist(song.artistName),
                    album = song.albumName,
                )
            }
        }

    override suspend fun getLookup(videoId: String) = withContext(Dispatchers.IO) { dao.getGenreLookup(videoId) }

    override suspend fun saveLookup(lookup: GenreLookupEntity) = withContext(Dispatchers.IO) { dao.upsertGenreLookup(lookup) }

    override suspend fun replaceTags(
        videoId: String,
        source: GenreSource,
        tags: List<NormalizedTag>,
        fetchedAt: Long,
    ) = withContext(Dispatchers.IO) {
        dao.replaceGenreTags(
            videoId,
            source.name,
            tags.map { SongGenreTagEntity(videoId, it.key, it.label, source.name, it.weight, fetchedAt) },
        )
    }

    override suspend fun getTags(videoId: String) = withContext(Dispatchers.IO) { dao.getGenreTags(videoId) }

    override suspend fun addToGenrePlaylists(
        videoId: String,
        genres: List<MergedGenre>,
        playlistTitle: (String) -> String,
    ): Int =
        withContext(Dispatchers.IO) {
            genres.count { dao.addSongToGenrePlaylist(it.key, playlistTitle(it.label), videoId) }
        }

    override suspend fun nextDue(now: Long) = withContext(Dispatchers.IO) { dao.nextDueGenreLookup(now) }

    override suspend fun earliestPending() = withContext(Dispatchers.IO) { dao.earliestPendingGenreLookup() }

    override suspend fun reopenGaveUp(olderThan: Long) = withContext(Dispatchers.IO) { dao.reopenGaveUpGenreLookups(olderThan) }
}
