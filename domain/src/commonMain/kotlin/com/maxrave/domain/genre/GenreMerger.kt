package com.maxrave.domain.genre

/**
 * Picks the genres to keep from what every provider returned.
 *
 * Score: each source contributes `weight / 100`, combined as a noisy-or (`1 - Π(1 - w)`), so two
 * sources agreeing on a tag rank it above either alone, and no score exceeds 1.
 *
 * A genre is kept when ANY of these holds:
 *  - score >= [KEEP_SCORE]: clearly relevant on its own;
 *  - both sources returned it (each at weight >= [CONFIRMED_MIN_WEIGHT]): corroborated, even if weak;
 *  - it is the single best genre and score >= [BEST_MIN_SCORE]: a song with only weak tags still
 *    gets its most likely genre rather than none.
 * At most [MAX_GENRES] are kept, best first. Nothing is ever invented: every result comes from a tag.
 */
object GenreMerger {
    const val KEEP_SCORE = 0.40
    const val CONFIRMED_MIN_WEIGHT = 15
    const val BEST_MIN_SCORE = 0.20
    const val MAX_GENRES = 5

    fun merge(tags: List<NormalizedTag>): List<MergedGenre> {
        val ranked =
            tags
                .groupBy { it.key }
                .map { (key, group) ->
                    // One value per source (the strongest), so duplicate rows cannot inflate a score.
                    val perSource = group.groupBy { it.source }.mapValues { (_, v) -> v.maxOf { it.weight } }
                    val score = 1.0 - perSource.values.fold(1.0) { acc, w -> acc * (1.0 - w / 100.0) }
                    val confirmed =
                        perSource.size > 1 && perSource.values.all { it >= CONFIRMED_MIN_WEIGHT }
                    Triple(
                        MergedGenre(
                            key = key,
                            // Label of the heaviest spelling, ties broken by key for determinism.
                            label = group.maxWith(compareBy<NormalizedTag> { it.weight }.thenBy { it.label }).label,
                            score = score,
                            sources = perSource.keys,
                        ),
                        confirmed,
                        group.size,
                    )
                }.sortedWith(compareByDescending<Triple<MergedGenre, Boolean, Int>> { it.first.score }.thenBy { it.first.key })

        return ranked
            .filterIndexed { index, (genre, confirmed, _) ->
                genre.score >= KEEP_SCORE || confirmed || (index == 0 && genre.score >= BEST_MIN_SCORE)
            }.map { it.first }
            .take(MAX_GENRES)
    }
}
