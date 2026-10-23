package com.maxrave.data.genre

import com.maxrave.domain.genre.GenreSource
import com.maxrave.domain.genre.GenreTagProvider
import com.maxrave.domain.genre.ProviderResult
import com.maxrave.domain.genre.RawGenreTag
import com.maxrave.domain.genre.TrackQuery
import com.maxrave.domain.genre.TrackTextCleaner
import org.simpmusic.lastfm.LastfmTagsOutcome
import org.simpmusic.lastfm.fetchTrackTags

/**
 * Genres from Last.fm `track.getTopTags`, through the existing `lastfm` module.
 *
 * Exact artist + title lookup with Last.fm's autocorrect off (see `LastfmClient.getTopTags`), so a
 * track Last.fm does not know yields "no match" rather than another track's tags. Without a
 * configured API key (FOSS builds, or no key in `local.properties`) it reports itself unavailable.
 */
internal class LastfmGenreProvider(
    private val lookup: suspend (artist: String, track: String) -> LastfmTagsOutcome = ::fetchTrackTags,
    // Last.fm allows about 5 requests per second per IP; one every 300 ms stays well under it.
    private val gate: MinIntervalGate = MinIntervalGate(300L),
) : GenreTagProvider {
    override val source = GenreSource.LASTFM

    override suspend fun fetch(query: TrackQuery): ProviderResult {
        val artist = query.artist ?: return ProviderResult.NoMatch
        val title = TrackTextCleaner.cleanTitle(query.title).ifBlank { return ProviderResult.NoMatch }
        return when (val outcome = gate.run { lookup(artist, title) }) {
            is LastfmTagsOutcome.Tags -> ProviderResult.Tags(outcome.tags.map { RawGenreTag(it.name, it.count) })
            LastfmTagsOutcome.NotFound -> ProviderResult.NoMatch
            is LastfmTagsOutcome.Temporary -> ProviderResult.TemporaryError(outcome.message)
            is LastfmTagsOutcome.Rejected -> ProviderResult.Unavailable
        }
    }
}
