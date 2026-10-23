package com.maxrave.domain.genre

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenreBackoffAndTextTest {
    @Test
    fun backoffGrowsExponentiallyThenCaps() {
        val d = (1..12).map { GenreBackoff.delayMillis(it, 0.5) }
        assertEquals(60_000L, d[0])
        assertEquals(120_000L, d[1])
        assertEquals(240_000L, d[2])
        assertTrue(d.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(6 * 60 * 60 * 1000L, d.last())
    }

    @Test
    fun jitterStaysWithinQuarterEitherSide() {
        assertEquals(45_000L, GenreBackoff.delayMillis(1, 0.0))
        assertEquals(75_000L, GenreBackoff.delayMillis(1, 1.0))
        assertEquals(75_000L, GenreBackoff.delayMillis(1, 7.0)) // out-of-range random is clamped
    }

    @Test
    fun cleanTitleRemovesPackagingButKeepsTheSong() {
        assertEquals("Creep", TrackTextCleaner.cleanTitle("Creep (Official Video)"))
        assertEquals("Song", TrackTextCleaner.cleanTitle("Song (feat. Someone)"))
        assertEquals("Song", TrackTextCleaner.cleanTitle("Song - Remastered 2011"))
        assertEquals("Song", TrackTextCleaner.cleanTitle("Song [HD]"))
        assertEquals("Song (Club Remix)", TrackTextCleaner.cleanTitle("Song (Club Remix)"))
    }

    @Test
    fun variantsAreDetected() {
        assertTrue(TrackTextCleaner.isVariant("Song (Club Remix)"))
        assertTrue(TrackTextCleaner.isVariant("Song - Live at Wembley"))
        assertFalse(TrackTextCleaner.isVariant("Song"))
    }

    @Test
    fun primaryArtistStripsTopicAndBlanks() {
        assertEquals("Radiohead", TrackTextCleaner.primaryArtist(listOf("Radiohead - Topic", "Other")))
        assertEquals("B", TrackTextCleaner.primaryArtist(listOf(" ", "B")))
        assertNull(TrackTextCleaner.primaryArtist(null))
        assertNull(TrackTextCleaner.primaryArtist(listOf("")))
    }

    @Test
    fun comparableIgnoresCaseAndPunctuation() {
        assertEquals(TrackTextCleaner.comparable("Don't Stop!"), TrackTextCleaner.comparable("dont stop"))
    }
}
