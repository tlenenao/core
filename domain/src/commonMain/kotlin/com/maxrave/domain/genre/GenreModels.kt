package com.maxrave.domain.genre

enum class GenreSource { LASTFM, MUSICBRAINZ }

/** What is asked of a provider. Built from the song's own metadata; originals are never altered. */
data class TrackQuery(
    val videoId: String,
    val title: String,
    /** Primary artist only. Providers must not search without one: a title alone is ambiguous. */
    val artist: String?,
    val album: String? = null,
)

/** A tag as a provider returned it. [weight] is 0..100, or null when the provider gave none. */
data class RawGenreTag(
    val name: String,
    val weight: Int?,
)

sealed interface ProviderResult {
    data class Tags(
        val tags: List<RawGenreTag>,
    ) : ProviderResult

    /** The provider answered and found no confident match, or the match had no tags. Final. */
    data object NoMatch : ProviderResult

    /** The provider cannot run in this build or configuration (e.g. no Last.fm key). Final. */
    data object Unavailable : ProviderResult

    /** Network, rate limit, server error. The query itself was never judged. Retry later. */
    data class TemporaryError(
        val reason: String,
    ) : ProviderResult
}

interface GenreTagProvider {
    val source: GenreSource

    suspend fun fetch(query: TrackQuery): ProviderResult
}

/** A tag after normalisation, ready to store or merge. */
data class NormalizedTag(
    val key: String,
    val label: String,
    val source: GenreSource,
    val weight: Int,
)

/** The outcome of merging every provider: one entry per genre, with where it came from. */
data class MergedGenre(
    val key: String,
    val label: String,
    /** 0.0..1.0. Noisy-or of the per-source weights, so agreement between sources raises it. */
    val score: Double,
    val sources: Set<GenreSource>,
)
