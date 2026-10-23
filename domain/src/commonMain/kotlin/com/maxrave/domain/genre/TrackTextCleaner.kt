package com.maxrave.domain.genre

/**
 * Prepares a song's own metadata for searching. The originals are never touched; these functions
 * only produce the strings sent to providers and compared against their answers.
 */
object TrackTextCleaner {
    /** Marks a different recording from the original: their tags would not describe the song. */
    private val VARIANT = Regex("""\b(remix|live|cover|acoustic|instrumental|karaoke|mashup|bootleg|edit|vip|sped up|slowed|nightcore|reverb)\b""", RegexOption.IGNORE_CASE)

    /** Packaging noise YouTube titles carry; the recording is the same without it. */
    private val NOISE =
        Regex(
            """\s*[(\[]\s*(official\s*(music\s*)?(video|audio|lyric video|visuali[sz]er)|lyrics?|lyric video|audio|video|hd|hq|4k|explicit|clean|(\d{4}\s*)?(re)?master(ed)?(\s*\d{4})?|remaster(ed)?\s*\d{0,4}|(feat|ft|featuring)\.?[^)\]]*)\s*[)\]]""",
            RegexOption.IGNORE_CASE,
        )
    private val TRAILING_REMASTER = Regex("""\s+-\s+(\d{4}\s+)?(re)?master(ed)?(\s+\d{4})?(\s+version)?\s*$""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE)
    private val TOPIC_SUFFIX = Regex("""\s*-\s*topic\s*$""", RegexOption.IGNORE_CASE)

    /** Title without packaging noise, "feat." credits and remaster markers. */
    fun cleanTitle(title: String): String =
        title
            .let { NOISE.replace(it, "") }
            .let { TRAILING_REMASTER.replace(it, "") }
            .let { FEAT_TAIL.replace(it, "") }
            .trim()

    /** Primary artist: the first credited one, without YouTube's " - Topic" suffix. */
    fun primaryArtist(artists: List<String>?): String? =
        artists
            ?.firstOrNull { it.isNotBlank() }
            ?.let { TOPIC_SUFFIX.replace(it, "") }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun isVariant(title: String): Boolean = VARIANT.containsMatchIn(title)

    /** Lower-case letters and digits only, for tolerant equality ("Don't Stop" == "dont stop"). */
    fun comparable(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}
