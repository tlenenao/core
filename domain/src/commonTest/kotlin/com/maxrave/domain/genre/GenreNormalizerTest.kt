package com.maxrave.domain.genre

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenreNormalizerTest {
    @Test
    fun caseAndSpacingVariantsShareOneKey() {
        val keys = listOf("Hip-Hop", "hip hop", "  HIPHOP ", "hip-hop/rap").map { GenreNormalizer.normalize(it)?.first }
        assertEquals(setOf("hip-hop"), keys.toSet())
        assertEquals("Hip-Hop", GenreNormalizer.normalize("hiphop")?.second)
    }

    @Test
    fun rnbSpellingsMerge() {
        val keys = listOf("R&B", "rnb", "RnB", "r and b", "Rhythm and Blues").map { GenreNormalizer.normalize(it)?.first }
        assertEquals(setOf("r-and-b"), keys.toSet())
        assertEquals("R&B", GenreNormalizer.normalize("rnb")?.second)
    }

    @Test
    fun unknownGenreGetsReadableLabelAndStableKey() {
        assertEquals("shoegaze" to "Shoegaze", GenreNormalizer.normalize("Shoegaze"))
        assertEquals("math-rock" to "Math Rock", GenreNormalizer.normalize("math  rock"))
        assertEquals("rock" to "Rock", GenreNormalizer.normalize("rock music"))
    }

    @Test
    fun specificStyleIsNotFoldedIntoParent() {
        assertEquals("indie-rock", GenreNormalizer.normalize("indie rock")?.first)
        assertEquals("rock", GenreNormalizer.normalize("rock")?.first)
    }

    @Test
    fun nonGenresAreRejected() {
        listOf("", "   ", "x", "seen live", "Favorites", "female vocalists", "90s", "2000s", "french", "a".repeat(41), "!!!")
            .forEach { assertNull(GenreNormalizer.normalize(it), "should reject '$it'") }
    }

    @Test
    fun normalizeAllDedupesKeepsHeaviestAndSorts() {
        val out =
            GenreNormalizer.normalizeAll(
                GenreSource.LASTFM,
                listOf(
                    RawGenreTag("Hip-Hop", 40),
                    RawGenreTag("hip hop", 90),
                    RawGenreTag("seen live", 100),
                    RawGenreTag("Jazz", 60),
                ),
            )
        assertEquals(listOf("hip-hop", "jazz"), out.map { it.key })
        assertEquals(90, out.first().weight)
    }

    @Test
    fun tagsRepeatingArtistOrTitleAreDropped() {
        val out =
            GenreNormalizer.normalizeAll(
                GenreSource.LASTFM,
                listOf(RawGenreTag("Radiohead", 100), RawGenreTag("Creep", 80), RawGenreTag("alternative rock", 70)),
                exclude = listOf("Radiohead", "Creep"),
            )
        assertEquals(listOf("alternative-rock"), out.map { it.key })
    }

    @Test
    fun missingWeightGetsDefaultAndOutOfRangeIsClamped() {
        val out = GenreNormalizer.normalizeAll(GenreSource.MUSICBRAINZ, listOf(RawGenreTag("funk", null), RawGenreTag("soul", 500)))
        assertEquals(mapOf("funk" to GenreNormalizer.DEFAULT_WEIGHT, "soul" to 100), out.associate { it.key to it.weight })
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertTrue(GenreNormalizer.normalizeAll(GenreSource.LASTFM, emptyList()).isEmpty())
    }
}
