package com.maxrave.domain.genre

/**
 * Turns the free-form tags providers return into stable genre identifiers.
 *
 * Rules, in order:
 *  1. Lower-case, trim, collapse whitespace; drop a trailing " music" ("rock music" -> "rock").
 *  2. Reject tags that are not a classification: empty or absurdly long, listener chatter
 *     ("seen live", "favorites"), vocal-gender markers, decades, bare nationalities.
 *  3. Map known spellings of the same genre to one key ([ALIASES], looked up on the letters and
 *     digits only, so "hip-hop", "Hip Hop" and "hiphop" meet).
 *  4. Otherwise the key is the words joined by "-"; the label is the words title-cased.
 *
 * A precise style is never folded into its parent ("indie rock" stays apart from "rock"): that
 * would invent a classification the providers did not give.
 */
object GenreNormalizer {
    private const val MAX_LENGTH = 40

    /** compact spelling -> (key, label). Only unambiguous variants of one genre belong here. */
    private val ALIASES: Map<String, Pair<String, String>> =
        mapOf(
            "hiphop" to ("hip-hop" to "Hip-Hop"),
            "hiphoprap" to ("hip-hop" to "Hip-Hop"),
            "rnb" to ("r-and-b" to "R&B"),
            "randb" to ("r-and-b" to "R&B"),
            "rb" to ("r-and-b" to "R&B"),
            "rhythmandblues" to ("r-and-b" to "R&B"),
            "electronica" to ("electronic" to "Electronic"),
            "electronicmusic" to ("electronic" to "Electronic"),
            "drumandbass" to ("drum-and-bass" to "Drum and Bass"),
            "drumnbass" to ("drum-and-bass" to "Drum and Bass"),
            "dnb" to ("drum-and-bass" to "Drum and Bass"),
            "dandb" to ("drum-and-bass" to "Drum and Bass"),
            "kpop" to ("k-pop" to "K-Pop"),
            "jpop" to ("j-pop" to "J-Pop"),
            "lofi" to ("lo-fi" to "Lo-Fi"),
            "synthpop" to ("synth-pop" to "Synth-Pop"),
            "indierock" to ("indie-rock" to "Indie Rock"),
            "indiepop" to ("indie-pop" to "Indie Pop"),
            "altrock" to ("alternative-rock" to "Alternative Rock"),
            "alternativerock" to ("alternative-rock" to "Alternative Rock"),
            "heavymetal" to ("heavy-metal" to "Heavy Metal"),
            "poprock" to ("pop-rock" to "Pop Rock"),
            "postrock" to ("post-rock" to "Post-Rock"),
            "postpunk" to ("post-punk" to "Post-Punk"),
            "triphop" to ("trip-hop" to "Trip-Hop"),
            "edm" to ("edm" to "EDM"),
        )

    /** Tags that say nothing about the music, or describe the listener rather than the track. */
    private val NOT_A_GENRE: Set<String> =
        setOf(
            "seen live", "favorites", "favorite", "favourites", "favourite", "love", "loved",
            "loved tracks", "my music", "awesome", "good", "best", "amazing", "beautiful", "cool",
            "great", "nice", "perfect", "epic", "fav", "favs", "albums i own", "check out",
            "male vocalists", "male vocalist", "female vocalists", "female vocalist", "vocalists",
            "vocalist", "male", "female", "spotify", "youtube", "music", "other", "misc", "unknown",
            "various", "soundtrack", "under 2000 listeners", "all", "new", "old", "classic",
            // nationalities and languages on their own
            "american", "british", "english", "french", "german", "swedish", "canadian", "australian",
            "italian", "spanish", "japanese", "korean", "russian", "brazilian", "irish", "scottish",
        )

    private val DECADE = Regex("""^(\d{2}|\d{4})'?s$""")
    private val SEPARATORS = Regex("""[^\p{L}\p{N}&]+""")

    /** Returns the identifier and display label, or null when [raw] is not a usable genre. */
    fun normalize(raw: String): Pair<String, String>? {
        var text = raw.trim().lowercase().replace(Regex("""\s+"""), " ")
        if (text.endsWith(" music")) text = text.removeSuffix(" music").trim()
        if (text.length < 2 || text.length > MAX_LENGTH) return null
        if (text in NOT_A_GENRE || DECADE.matches(text)) return null

        val compact = text.filter { it.isLetterOrDigit() || it == '&' }.replace("&", "and")
        if (compact.length < 2) return null
        ALIASES[compact]?.let { return it }
        // "r&b"-style spellings are caught above; here "&" only separates words.
        val words = text.split(SEPARATORS).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val key = words.joinToString("-")
        val label = words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        return key to label
    }

    /**
     * Normalises one provider's tags: drops non-genres and tags that merely repeat [exclude]
     * (artist, title — Last.fm users tag with both), keeps the heaviest weight when several
     * spellings collapse to one key, orders by weight and caps the list.
     */
    fun normalizeAll(
        source: GenreSource,
        tags: List<RawGenreTag>,
        exclude: Collection<String> = emptyList(),
        limit: Int = 15,
    ): List<NormalizedTag> {
        val excluded = exclude.mapNotNull { normalize(it)?.first ?: it.trim().lowercase().takeIf(String::isNotEmpty) }.toSet()
        return tags
            .mapNotNull { tag ->
                val (key, label) = normalize(tag.name) ?: return@mapNotNull null
                if (key in excluded || tag.name.trim().lowercase() in excluded) return@mapNotNull null
                NormalizedTag(key, label, source, (tag.weight ?: DEFAULT_WEIGHT).coerceIn(0, 100))
            }.groupBy { it.key }
            .map { (_, group) -> group.maxBy { it.weight } }
            .sortedByDescending { it.weight }
            .take(limit)
    }

    /** Weight assumed for a provider that ranks tags without scoring them. */
    const val DEFAULT_WEIGHT = 50
}
