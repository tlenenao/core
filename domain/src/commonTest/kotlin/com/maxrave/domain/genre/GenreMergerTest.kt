package com.maxrave.domain.genre

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenreMergerTest {
    private fun tag(
        key: String,
        source: GenreSource,
        weight: Int,
    ) = NormalizedTag(key, key.replaceFirstChar { it.uppercase() }, source, weight)

    @Test
    fun emptyInputGivesNothing() {
        assertTrue(GenreMerger.merge(emptyList()).isEmpty())
    }

    @Test
    fun agreementBetweenSourcesRaisesScoreAndKeepsProvenance() {
        val merged =
            GenreMerger.merge(
                listOf(tag("jazz", GenreSource.LASTFM, 50), tag("jazz", GenreSource.MUSICBRAINZ, 50)),
            )
        val jazz = merged.single()
        assertEquals(setOf(GenreSource.LASTFM, GenreSource.MUSICBRAINZ), jazz.sources)
        assertEquals(0.75, jazz.score, 1e-9)
    }

    @Test
    fun duplicateRowsOfOneSourceDoNotInflateScore() {
        val merged = GenreMerger.merge(listOf(tag("rock", GenreSource.LASTFM, 50), tag("rock", GenreSource.LASTFM, 50)))
        assertEquals(0.5, merged.single().score, 1e-9)
    }

    @Test
    fun weakGenresAreDroppedButTheBestOneSurvives() {
        val merged = GenreMerger.merge(listOf(tag("a", GenreSource.LASTFM, 30), tag("b", GenreSource.LASTFM, 25), tag("c", GenreSource.LASTFM, 5)))
        assertEquals(listOf("a"), merged.map { it.key })
    }

    @Test
    fun tooWeakToEvenBeBestIsDropped() {
        assertTrue(GenreMerger.merge(listOf(tag("a", GenreSource.LASTFM, 10))).isEmpty())
    }

    @Test
    fun secondaryGenresAboveThresholdAreKept() {
        val merged = GenreMerger.merge(listOf(tag("rock", GenreSource.LASTFM, 100), tag("blues", GenreSource.LASTFM, 45)))
        assertEquals(listOf("rock", "blues"), merged.map { it.key })
    }

    @Test
    fun weakButCorroboratedGenreIsKept() {
        val merged =
            GenreMerger.merge(
                listOf(
                    tag("pop", GenreSource.LASTFM, 100),
                    tag("synth-pop", GenreSource.LASTFM, 20),
                    tag("synth-pop", GenreSource.MUSICBRAINZ, 20),
                ),
            )
        assertEquals(listOf("pop", "synth-pop"), merged.map { it.key })
    }

    @Test
    fun contradictorySourcesBothContribute() {
        val merged = GenreMerger.merge(listOf(tag("metal", GenreSource.LASTFM, 90), tag("jazz", GenreSource.MUSICBRAINZ, 80)))
        assertEquals(listOf("metal", "jazz"), merged.map { it.key })
    }

    @Test
    fun resultIsCappedAndDeterministic() {
        val tags = (1..9).map { tag("g$it", GenreSource.LASTFM, 90) }
        val first = GenreMerger.merge(tags)
        assertEquals(GenreMerger.MAX_GENRES, first.size)
        assertEquals(first, GenreMerger.merge(tags.reversed()))
    }
}
