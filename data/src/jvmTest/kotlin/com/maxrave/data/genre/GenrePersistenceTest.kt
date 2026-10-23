package com.maxrave.data.genre

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.genre.GenreLookupEntity
import com.maxrave.domain.data.entities.genre.GenreLookupEntity.ProviderState
import com.maxrave.domain.genre.GenreEnrichment
import com.maxrave.domain.genre.GenreSource
import com.maxrave.domain.genre.GenreTagProvider
import com.maxrave.domain.genre.NormalizedTag
import com.maxrave.domain.genre.ProviderResult
import com.maxrave.domain.genre.RawGenreTag
import com.maxrave.domain.genre.TrackQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun song(
    id: String,
    title: String = "Title $id",
    artist: String = "Artist",
) = SongEntity(
    videoId = id,
    artistName = listOf(artist),
    duration = "3:00",
    durationSeconds = 180,
    isAvailable = true,
    isExplicit = false,
    likeStatus = "INDIFFERENT",
    title = title,
    videoType = "MUSIC_VIDEO_TYPE_ATV",
    category = null,
    resultType = null,
    liked = true,
)

class GenrePersistenceTest {
    private val files = mutableListOf<File>()
    private val dbs = mutableListOf<MusicDatabase>()

    private fun memoryDb(): MusicDatabase =
        Room
            .inMemoryDatabaseBuilder<MusicDatabase>()
            .addTypeConverter(Converters())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .also { dbs += it }

    private fun fileDb(file: File): MusicDatabase =
        Room
            .databaseBuilder<MusicDatabase>(file.absolutePath)
            .addTypeConverter(Converters())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .also { dbs += it }

    @AfterTest
    fun cleanup() {
        dbs.forEach { runCatching { it.close() } }
        files.forEach { f -> f.parentFile.listFiles()?.forEach { it.delete() }; f.parentFile.delete() }
    }

    @Test
    fun createsPlaylistOnceAndReusesIt() =
        runTest {
            val dao = memoryDb().getDatabaseDao()
            dao.insertSong(song("a"))
            dao.insertSong(song("b"))

            assertTrue(dao.addSongToGenrePlaylist("rock", "Genre · Rock", "a"))
            assertTrue(dao.addSongToGenrePlaylist("rock", "Genre · Rock", "b"))

            val playlists = dao.getAllLocalPlaylists(10, 0)
            assertEquals(listOf("Genre · Rock"), playlists.map { it.title })
            val p = playlists.single()
            assertEquals(listOf("a", "b"), p.tracks)
            assertEquals(listOf(0, 1), dao.getAllPlaylistPairSongByPosition(p.id).map { it.position })
        }

    @Test
    fun addingTheSameSongTwiceNeverDuplicates() =
        runTest {
            val dao = memoryDb().getDatabaseDao()
            dao.insertSong(song("a"))
            assertTrue(dao.addSongToGenrePlaylist("jazz", "Genre · Jazz", "a"))
            assertEquals(false, dao.addSongToGenrePlaylist("jazz", "Genre · Jazz", "a"))
            val p = dao.getAllLocalPlaylists(10, 0).single()
            assertEquals(1, dao.getAllPlaylistPairSongByPosition(p.id).size)
            assertEquals(listOf("a"), p.tracks)
        }

    @Test
    fun manualPlaylistWithTheSameTitleIsNeverTouchedOrReused() =
        runTest {
            val dao = memoryDb().getDatabaseDao()
            dao.insertSong(song("a"))
            val manualId = dao.insertLocalPlaylist(LocalPlaylistEntity(title = "Genre · Rock"))

            dao.addSongToGenrePlaylist("rock", "Genre · Rock", "a")

            val all = dao.getAllLocalPlaylists(10, 0)
            assertEquals(2, all.size)
            assertNull(dao.getLocalPlaylist(manualId)?.tracks)
            assertEquals(0, dao.getAllPlaylistPairSongByPosition(manualId).size)
        }

    @Test
    fun deletedAutomaticPlaylistIsRecreatedByTheNextSong() =
        runTest {
            val dao = memoryDb().getDatabaseDao()
            dao.insertSong(song("a"))
            dao.insertSong(song("b"))
            dao.addSongToGenrePlaylist("rock", "Genre · Rock", "a")
            val first = dao.getGenrePlaylistId("rock")!!
            dao.deleteLocalPlaylist(first)
            assertNull(dao.getGenrePlaylistId("rock")) // mapping cascaded away

            assertTrue(dao.addSongToGenrePlaylist("rock", "Genre · Rock", "b"))
            val second = dao.getGenrePlaylistId("rock")!!
            assertTrue(second != first)
            assertEquals(listOf("b"), dao.getLocalPlaylist(second)?.tracks)
        }

    @Test
    fun removingFromFavouritesLeavesPlaylistsAlone() =
        runTest {
            val dao = memoryDb().getDatabaseDao()
            dao.insertSong(song("a"))
            dao.addSongToGenrePlaylist("rock", "Genre · Rock", "a")
            dao.updateLiked(0, "a")
            val id = dao.getGenrePlaylistId("rock")!!
            assertEquals(1, dao.getAllPlaylistPairSongByPosition(id).size)
        }

    @Test
    fun replacingTagsOnlyAffectsThatSource() =
        runTest {
            val repo = GenreRepositoryImpl(memoryDb().getDatabaseDao().also { it.insertSong(song("a")) })
            repo.replaceTags("a", GenreSource.LASTFM, listOf(NormalizedTag("rock", "Rock", GenreSource.LASTFM, 90)), 1)
            repo.replaceTags("a", GenreSource.MUSICBRAINZ, listOf(NormalizedTag("rock", "Rock", GenreSource.MUSICBRAINZ, 80)), 2)
            repo.replaceTags("a", GenreSource.LASTFM, listOf(NormalizedTag("blues", "Blues", GenreSource.LASTFM, 70)), 3)

            val tags = repo.getTags("a").map { Triple(it.genreKey, it.source, it.weight) }.toSet()
            assertEquals(setOf(Triple("blues", "LASTFM", 70), Triple("rock", "MUSICBRAINZ", 80)), tags)
        }

    @Test
    fun lookupQueueingAndReopeningRules() =
        runTest {
            val dao = memoryDb().getDatabaseDao()
            dao.insertSong(song("due"))
            dao.insertSong(song("later"))
            dao.insertSong(song("done"))
            dao.insertSong(song("gaveup"))
            dao.upsertGenreLookup(GenreLookupEntity("due", nextAttemptAt = 100))
            dao.upsertGenreLookup(GenreLookupEntity("later", nextAttemptAt = 5_000))
            dao.upsertGenreLookup(GenreLookupEntity("done", finished = true, lastfmState = ProviderState.NO_RESULT, musicbrainzState = ProviderState.NO_RESULT, updatedAt = 1))
            dao.upsertGenreLookup(GenreLookupEntity("gaveup", attempts = 6, finished = true, lastfmState = ProviderState.TEMPORARY_ERROR, updatedAt = 1))

            assertEquals("due", dao.nextDueGenreLookup(1_000))
            assertEquals(100L, dao.earliestPendingGenreLookup())
            assertNull(dao.nextDueGenreLookup(50))

            assertEquals(1, dao.reopenGaveUpGenreLookups(olderThan = 1_000)) // only the one that failed
            val reopened = dao.getGenreLookup("gaveup")!!
            assertEquals(0, reopened.attempts)
            assertEquals(false, reopened.finished)
            assertEquals(true, dao.getGenreLookup("done")!!.finished) // settled "no result" stays settled
        }

    @Test
    fun tagsAndLookupGoWhenTheSongIsDeleted() =
        runTest {
            val dao = memoryDb().getDatabaseDao()
            dao.insertSong(song("a").copy(liked = false)) // a liked song is spared by the sweep
            val repo = GenreRepositoryImpl(dao)
            repo.replaceTags("a", GenreSource.LASTFM, listOf(NormalizedTag("rock", "Rock", GenreSource.LASTFM, 90)), 1)
            repo.saveLookup(GenreLookupEntity("a"))
            assertEquals(1, dao.deleteSongsByIds(listOf("a")))
            assertTrue(repo.getTags("a").isEmpty())
            assertNull(repo.getLookup("a"))
        }

    @Test
    fun everythingSurvivesReopeningTheDatabase() =
        runTest {
            val dir = File.createTempFile("genre", "db").let { it.delete(); it.mkdirs(); it }
            val file = File(dir, "music.db").also { files += it }

            val first = fileDb(file)
            first.getDatabaseDao().insertSong(song("a"))
            val repo1 = GenreRepositoryImpl(first.getDatabaseDao())
            repo1.replaceTags("a", GenreSource.LASTFM, listOf(NormalizedTag("rock", "Rock", GenreSource.LASTFM, 90)), 1)
            repo1.saveLookup(GenreLookupEntity("a", lastfmState = ProviderState.OK, finished = true))
            repo1.addToGenrePlaylists("a", listOf(com.maxrave.domain.genre.MergedGenre("rock", "Rock", 0.9, setOf(GenreSource.LASTFM)))) { "Genre · $it" }
            first.close()

            val dao = fileDb(file).getDatabaseDao()
            assertEquals(listOf("rock"), dao.getGenreTags("a").map { it.genreKey })
            assertEquals(true, dao.getGenreLookup("a")?.finished)
            val playlist = assertNotNull(dao.getLocalPlaylist(dao.getGenrePlaylistId("rock")!!))
            assertEquals(listOf("a"), playlist.tracks)
        }

    @Test
    fun fullPipelineIsIdempotentAgainstARealDatabase() =
        runTest {
            val dao = memoryDb().getDatabaseDao()
            dao.insertSong(song("a", title = "Creep", artist = "Radiohead - Topic"))
            val queries = mutableListOf<TrackQuery>()
            val lastfm =
                object : GenreTagProvider {
                    override val source = GenreSource.LASTFM

                    override suspend fun fetch(query: TrackQuery): ProviderResult {
                        queries += query
                        return ProviderResult.Tags(listOf(RawGenreTag("Alternative Rock", 100), RawGenreTag("Radiohead", 100), RawGenreTag("seen live", 80)))
                    }
                }
            val mb =
                object : GenreTagProvider {
                    override val source = GenreSource.MUSICBRAINZ

                    override suspend fun fetch(query: TrackQuery) = ProviderResult.TemporaryError("503")
                }
            val e = GenreEnrichment(listOf(lastfm, mb), GenreRepositoryImpl(dao), now = { 10_000 }, random = { 0.5 })

            e.process("a")
            e.process("a")
            e.process("a")

            assertEquals("Radiohead", queries.first().artist) // " - Topic" stripped, original untouched
            assertEquals("Radiohead - Topic", dao.getSong("a")!!.artistName!!.single())
            assertEquals(1, queries.size) // Last.fm answered once; only MusicBrainz was retried
            val playlist = dao.getLocalPlaylist(dao.getGenrePlaylistId("alternative-rock")!!)!!
            assertEquals("Genre · Alternative Rock", playlist.title)
            assertEquals(listOf("a"), playlist.tracks)
            assertEquals(1, dao.getAllLocalPlaylists(10, 0).size)
            assertEquals(3, dao.getGenreLookup("a")!!.attempts)
        }
}
