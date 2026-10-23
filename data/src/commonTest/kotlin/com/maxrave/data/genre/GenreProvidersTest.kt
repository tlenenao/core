package com.maxrave.data.genre

import com.maxrave.domain.genre.ProviderResult
import com.maxrave.domain.genre.RawGenreTag
import com.maxrave.domain.genre.TrackQuery
import kotlinx.coroutines.test.runTest
import org.simpmusic.lastfm.LastfmTag
import org.simpmusic.lastfm.LastfmTagsOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun recording(
    title: String,
    artist: String,
    score: Int = 100,
    tags: String = """[{"count":10,"name":"rock"},{"count":5,"name":"alternative rock"},{"count":0,"name":"junk"}]""",
) = """{"id":"x","score":$score,"title":"$title","artist-credit":[{"name":"$artist","artist":{"name":"$artist"}}],"tags":$tags}"""

private fun body(vararg recs: String) = """{"count":${recs.size},"recordings":[${recs.joinToString(",")}]}"""

private class FakeHttp(
    private val respond: () -> GenreHttpResponse,
) : GenreHttpGet {
    val urls = mutableListOf<String>()
    val headers = mutableListOf<Map<String, String>>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): GenreHttpResponse {
        urls += url
        this.headers += headers
        return respond()
    }
}

private val noWait = MinIntervalGate(0)
private val song = TrackQuery("v1", "Creep (Official Video)", "Radiohead")

class MusicBrainzGenreProviderTest {
    private fun provider(http: GenreHttpGet) = MusicBrainzGenreProvider(http, noWait)

    @Test
    fun matchingRecordingYieldsRelativeWeights() =
        runTest {
            val http = FakeHttp { GenreHttpResponse(200, body(recording("Creep", "Radiohead"))) }
            val result = assertIs<ProviderResult.Tags>(provider(http).fetch(song))
            assertEquals(listOf(RawGenreTag("rock", 100), RawGenreTag("alternative rock", 50)), result.tags)
        }

    @Test
    fun requestIsIdentifiedEscapedAndSearchesCleanedTitle() =
        runTest {
            val http = FakeHttp { GenreHttpResponse(200, body()) }
            provider(http).fetch(TrackQuery("v", "Say \"Hi\" (Remastered 2011)", "AC/DC"))
            assertTrue(http.headers.single().getValue("User-Agent").contains("github.com/tlenenao/SimpMusic"))
            val url = http.urls.single()
            assertTrue(url.startsWith("https://musicbrainz.org/ws/2/recording?query="))
            assertTrue("%5C%22Hi%5C%22" in url, url) // quotes escaped for Lucene, then URL-encoded
            assertTrue("Remastered" !in url)
        }

    @Test
    fun sameTitleByAnotherArtistIsNotAccepted() =
        runTest {
            val http = FakeHttp { GenreHttpResponse(200, body(recording("Creep", "TLC"))) }
            assertEquals(ProviderResult.NoMatch, provider(http).fetch(song))
        }

    @Test
    fun lowScoreAndDifferentVersionAreNotAccepted() =
        runTest {
            val low = FakeHttp { GenreHttpResponse(200, body(recording("Creep", "Radiohead", score = 70))) }
            assertEquals(ProviderResult.NoMatch, provider(low).fetch(song))
            val remix = FakeHttp { GenreHttpResponse(200, body(recording("Creep (Club Remix)", "Radiohead"))) }
            assertEquals(ProviderResult.NoMatch, provider(remix).fetch(song))
        }

    @Test
    fun remixQueryDoesNotMatchTheOriginal() =
        runTest {
            val http = FakeHttp { GenreHttpResponse(200, body(recording("Creep", "Radiohead"))) }
            assertEquals(ProviderResult.NoMatch, provider(http).fetch(TrackQuery("v", "Creep (Club Remix)", "Radiohead")))
        }

    @Test
    fun matchesPooledAcrossReleasesAndArtistCreditTolerance() =
        runTest {
            val http =
                FakeHttp {
                    GenreHttpResponse(
                        200,
                        body(
                            recording("Creep", "Radiohead", tags = """[{"count":4,"name":"rock"}]"""),
                            recording("Creep", "The Radiohead", tags = """[{"count":4,"name":"rock"},{"count":2,"name":"grunge"}]"""),
                        ),
                    )
                }
            val tags = assertIs<ProviderResult.Tags>(provider(http).fetch(song)).tags
            assertEquals(listOf(RawGenreTag("rock", 100), RawGenreTag("grunge", 25)), tags)
        }

    @Test
    fun matchWithoutUsableTagsIsNoMatch() =
        runTest {
            val http = FakeHttp { GenreHttpResponse(200, body(recording("Creep", "Radiohead", tags = "[]"))) }
            assertEquals(ProviderResult.NoMatch, provider(http).fetch(song))
        }

    @Test
    fun noArtistMeansNoRequest() =
        runTest {
            val http = FakeHttp { error("must not be called") }
            assertEquals(ProviderResult.NoMatch, provider(http).fetch(TrackQuery("v", "Creep", null)))
            assertTrue(http.urls.isEmpty())
        }

    @Test
    fun rateLimitServerErrorAndNetworkFailureAreTemporary() =
        runTest {
            assertIs<ProviderResult.TemporaryError>(provider(FakeHttp { GenreHttpResponse(503, "") }).fetch(song))
            assertIs<ProviderResult.TemporaryError>(provider(FakeHttp { GenreHttpResponse(429, "") }).fetch(song))
            assertIs<ProviderResult.TemporaryError>(provider(FakeHttp { GenreHttpResponse(200, "<html>") }).fetch(song))
            assertIs<ProviderResult.TemporaryError>(provider(FakeHttp { throw IllegalStateException("offline") }).fetch(song))
        }

    @Test
    fun refusedRequestIsFinalNotRetried() =
        runTest {
            assertEquals(ProviderResult.NoMatch, provider(FakeHttp { GenreHttpResponse(400, "bad query") }).fetch(song))
        }
}

class LastfmGenreProviderTest {
    private fun provider(outcome: LastfmTagsOutcome) = LastfmGenreProvider({ _, _ -> outcome }, noWait)

    @Test
    fun mapsEveryOutcome() =
        runTest {
            val tags = provider(LastfmTagsOutcome.Tags(listOf(LastfmTag("rock", 100), LastfmTag("indie", null)))).fetch(song)
            assertEquals(ProviderResult.Tags(listOf(RawGenreTag("rock", 100), RawGenreTag("indie", null))), tags)
            assertEquals(ProviderResult.NoMatch, provider(LastfmTagsOutcome.NotFound).fetch(song))
            assertEquals(ProviderResult.TemporaryError("x"), provider(LastfmTagsOutcome.Temporary("x")).fetch(song))
            assertEquals(ProviderResult.Unavailable, provider(LastfmTagsOutcome.Rejected("no key")).fetch(song))
        }

    @Test
    fun searchesExactCleanedTitleAndNeverWithoutArtist() =
        runTest {
            val seen = mutableListOf<Pair<String, String>>()
            val p = LastfmGenreProvider({ a, t -> seen += a to t; LastfmTagsOutcome.NotFound }, noWait)
            p.fetch(song)
            p.fetch(TrackQuery("v", "Creep", null))
            assertEquals(listOf("Radiohead" to "Creep"), seen)
        }
}

class MinIntervalGateTest {
    @Test
    fun spacesCallsByTheIntervalOnly() =
        runTest {
            var now = 0L
            val sleeps = mutableListOf<Long>()
            val gate = MinIntervalGate(1_000, { now }, { sleeps += it; now += it })
            gate.run { now += 100 } // first call: no wait; ends at t=100
            now += 100 // 100ms pass on their own
            gate.run { now += 10 } // 100ms elapsed since the last call ended -> waits 900
            now += 5_000
            gate.run { } // plenty elapsed -> no wait
            assertEquals(listOf(900L), sleeps)
        }
}
