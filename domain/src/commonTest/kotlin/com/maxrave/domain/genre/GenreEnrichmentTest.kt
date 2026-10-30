package com.maxrave.domain.genre

import com.maxrave.domain.data.entities.genre.GenreLookupEntity
import com.maxrave.domain.data.entities.genre.GenreLookupEntity.ProviderState
import com.maxrave.domain.data.entities.genre.SongGenreTagEntity
import com.maxrave.domain.repository.GenreRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeRepo(
    val songs: Map<String, TrackQuery>,
) : GenreRepository {
    val lookups = mutableMapOf<String, GenreLookupEntity>()
    val tags = mutableMapOf<Triple<String, String, String>, SongGenreTagEntity>()
    val playlists = mutableMapOf<String, MutableSet<String>>() // genreKey -> videoIds
    val liked = mutableSetOf<String>() // the "Liked" playlist, kept apart from the genre ones

    override suspend fun getTrackQuery(videoId: String) = songs[videoId]

    override suspend fun getLookup(videoId: String) = lookups[videoId]

    override suspend fun saveLookup(lookup: GenreLookupEntity) {
        lookups[lookup.videoId] = lookup
    }

    override suspend fun replaceTags(
        videoId: String,
        source: GenreSource,
        tags: List<NormalizedTag>,
        fetchedAt: Long,
    ) {
        this.tags.keys.removeAll { it.first == videoId && it.third == source.name }
        tags.forEach { this.tags[Triple(videoId, it.key, source.name)] = SongGenreTagEntity(videoId, it.key, it.label, source.name, it.weight, fetchedAt) }
    }

    override suspend fun getTags(videoId: String) = tags.values.filter { it.videoId == videoId }

    override suspend fun addToGenrePlaylists(
        videoId: String,
        genres: List<MergedGenre>,
        playlistTitle: (String) -> String,
    ): Int =
        genres.count {
            if (it.key == "__liked__") liked.add(videoId) else playlists.getOrPut(it.key) { mutableSetOf() }.add(videoId)
        }

    override suspend fun nextDue(now: Long) = lookups.values.firstOrNull { !it.finished && it.nextAttemptAt <= now }?.videoId

    override suspend fun earliestPending() = lookups.values.filter { !it.finished }.minOfOrNull { it.nextAttemptAt }

    override suspend fun reopenGaveUp(olderThan: Long) = 0
}

private class FakeProvider(
    override val source: GenreSource,
    var next: ProviderResult,
) : GenreTagProvider {
    var calls = 0

    override suspend fun fetch(query: TrackQuery): ProviderResult {
        calls++
        return next
    }
}

class GenreEnrichmentTest {
    private val song = TrackQuery("v1", "Creep", "Radiohead")
    private var clock = 1_000_000L

    private fun enrichment(
        repo: FakeRepo,
        vararg providers: GenreTagProvider,
    ) = GenreEnrichment(providers.toList(), repo, now = { clock }, random = { 0.5 })

    private fun tags(vararg pairs: Pair<String, Int>) = ProviderResult.Tags(pairs.map { RawGenreTag(it.first, it.second) })

    @Test
    fun filesSongIntoMergedGenrePlaylists() =
        runTest {
            val repo = FakeRepo(mapOf("v1" to song))
            val lastfm = FakeProvider(GenreSource.LASTFM, tags("alternative rock" to 100, "Radiohead" to 100, "seen live" to 90))
            val mb = FakeProvider(GenreSource.MUSICBRAINZ, tags("alternative rock" to 100, "grunge" to 60))
            val outcome = enrichment(repo, lastfm, mb).process("v1")
            assertIs<GenreEnrichment.Outcome.Done>(outcome)
            assertEquals(setOf("alternative-rock", "grunge"), repo.playlists.keys)
            assertEquals(setOf("v1"), repo.liked) // liked whatever the providers found
            assertTrue(repo.lookups.getValue("v1").finished)
        }

    @Test
    fun runningTwiceIsIdempotentAndUsesTheCache() =
        runTest {
            val repo = FakeRepo(mapOf("v1" to song))
            val lastfm = FakeProvider(GenreSource.LASTFM, tags("rock" to 100))
            val mb = FakeProvider(GenreSource.MUSICBRAINZ, ProviderResult.NoMatch)
            val e = enrichment(repo, lastfm, mb)
            e.process("v1")
            val snapshot = repo.playlists.mapValues { it.value.toSet() } to repo.tags.toMap()
            e.process("v1")
            assertEquals(snapshot, repo.playlists.mapValues { it.value.toSet() } to repo.tags.toMap())
            assertEquals(1, lastfm.calls)
            assertEquals(1, mb.calls)
        }

    @Test
    fun emptyResultIsFinalNotAnError() =
        runTest {
            val repo = FakeRepo(mapOf("v1" to song))
            val lastfm = FakeProvider(GenreSource.LASTFM, tags("seen live" to 100))
            val mb = FakeProvider(GenreSource.MUSICBRAINZ, ProviderResult.NoMatch)
            val outcome = enrichment(repo, lastfm, mb).process("v1")
            assertIs<GenreEnrichment.Outcome.Done>(outcome)
            assertTrue(repo.playlists.isEmpty())
            val l = repo.lookups.getValue("v1")
            assertEquals(ProviderState.NO_RESULT, l.lastfmState)
            assertEquals(ProviderState.NO_RESULT, l.musicbrainzState)
        }

    @Test
    fun oneProviderFailingDoesNotBlockTheOtherAndOnlyTheFailedOneIsRetried() =
        runTest {
            val repo = FakeRepo(mapOf("v1" to song))
            val lastfm = FakeProvider(GenreSource.LASTFM, tags("jazz" to 90))
            val mb = FakeProvider(GenreSource.MUSICBRAINZ, ProviderResult.TemporaryError("HTTP 503"))
            val e = enrichment(repo, lastfm, mb)

            val first = e.process("v1")
            assertIs<GenreEnrichment.Outcome.Retry>(first)
            assertEquals(setOf("jazz"), repo.playlists.keys) // partial result already filed
            assertEquals(clock + GenreBackoff.delayMillis(1, 0.5), first.nextAttemptAt)
            assertEquals(null, repo.nextDue(clock)) // not due yet

            clock = first.nextAttemptAt
            mb.next = tags("jazz" to 80, "bebop" to 70)
            val second = e.process("v1")
            assertIs<GenreEnrichment.Outcome.Done>(second)
            assertEquals(1, lastfm.calls) // never asked again
            assertEquals(2, mb.calls)
            assertEquals(setOf("jazz", "bebop"), repo.playlists.keys)
        }

    @Test
    fun networkDownEverywhereSchedulesRetryThenGivesUp() =
        runTest {
            val repo = FakeRepo(mapOf("v1" to song))
            val lastfm = FakeProvider(GenreSource.LASTFM, ProviderResult.TemporaryError("offline"))
            val mb = FakeProvider(GenreSource.MUSICBRAINZ, ProviderResult.TemporaryError("offline"))
            val e = enrichment(repo, lastfm, mb)
            var last: GenreEnrichment.Outcome? = null
            repeat(GenreBackoff.MAX_ATTEMPTS) {
                last = e.process("v1")
                clock += 24 * 60 * 60 * 1000L
            }
            assertIs<GenreEnrichment.Outcome.GaveUp>(last)
            assertEquals(GenreBackoff.MAX_ATTEMPTS, repo.lookups.getValue("v1").attempts)
            assertTrue(repo.lookups.getValue("v1").finished)
            assertEquals(GenreBackoff.MAX_ATTEMPTS, lastfm.calls) // bounded: no endless loop
        }

    @Test
    fun unavailableProviderIsSkippedWithoutRetry() =
        runTest {
            val repo = FakeRepo(mapOf("v1" to song))
            val lastfm = FakeProvider(GenreSource.LASTFM, ProviderResult.Unavailable)
            val mb = FakeProvider(GenreSource.MUSICBRAINZ, tags("funk" to 80))
            val outcome = enrichment(repo, lastfm, mb).process("v1")
            assertIs<GenreEnrichment.Outcome.Done>(outcome)
            assertEquals(ProviderState.SKIPPED, repo.lookups.getValue("v1").lastfmState)
        }

    @Test
    fun forceAsksEveryoneAgain() =
        runTest {
            val repo = FakeRepo(mapOf("v1" to song))
            val lastfm = FakeProvider(GenreSource.LASTFM, tags("rock" to 90))
            val e = enrichment(repo, lastfm)
            e.process("v1")
            lastfm.next = tags("rock" to 90, "blues" to 80)
            e.process("v1", force = true)
            assertEquals(2, lastfm.calls)
            assertEquals(setOf("rock", "blues"), repo.playlists.keys) // history kept, new genre added
        }

    @Test
    fun genreDisappearingFromNewResultIsNotRemovedFromPlaylists() =
        runTest {
            val repo = FakeRepo(mapOf("v1" to song))
            val lastfm = FakeProvider(GenreSource.LASTFM, tags("rock" to 90, "blues" to 80))
            val e = enrichment(repo, lastfm)
            e.process("v1")
            lastfm.next = tags("rock" to 90)
            e.process("v1", force = true)
            assertEquals(setOf("v1"), repo.playlists.getValue("blues"))
        }

    @Test
    fun unknownSongChangesNothing() =
        runTest {
            val repo = FakeRepo(emptyMap())
            val lastfm = FakeProvider(GenreSource.LASTFM, tags("rock" to 90))
            assertEquals(GenreEnrichment.Outcome.UnknownSong, enrichment(repo, lastfm).process("ghost"))
            assertEquals(0, lastfm.calls)
            assertTrue(repo.lookups.isEmpty())
        }

    @Test
    fun registerDoesNotOverwriteProgress() =
        runTest {
            val repo = FakeRepo(mapOf("v1" to song))
            repo.lookups["v1"] = GenreLookupEntity("v1", attempts = 3, finished = true)
            enrichment(repo).register("v1")
            assertEquals(3, repo.lookups.getValue("v1").attempts)
        }
}
