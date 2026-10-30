package com.maxrave.data.genre

import com.maxrave.domain.genre.GenreSource
import com.maxrave.domain.genre.GenreTagProvider
import com.maxrave.domain.genre.ProviderResult
import com.maxrave.domain.genre.RawGenreTag
import com.maxrave.domain.genre.TrackQuery
import com.maxrave.domain.genre.TrackTextCleaner
import io.ktor.http.encodeURLQueryComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Genres from MusicBrainz recording search.
 *
 * MusicBrainz exposes community *tags* (and, on some payloads, a curated `genres` list); this uses
 * `genres` when a result has them and falls back to `tags`. They are tags, not verified genres,
 * which is why they go through the same normalisation and filtering as Last.fm's.
 *
 * One search request, no per-recording lookups. A result is accepted only when its title AND an
 * artist credit match the song and the search score is high; a common title by another artist is
 * never accepted. Up to [MAX_MATCHES] matching recordings are pooled, since the same track appears
 * once per release.
 */
internal class MusicBrainzGenreProvider(
    private val http: GenreHttpGet,
    private val gate: MinIntervalGate = MinIntervalGate(MIN_INTERVAL_MILLIS),
) : GenreTagProvider {
    override val source = GenreSource.MUSICBRAINZ

    override suspend fun fetch(query: TrackQuery): ProviderResult {
        val artist = query.artist ?: return ProviderResult.NoMatch
        val title = TrackTextCleaner.cleanTitle(query.title).ifBlank { return ProviderResult.NoMatch }
        val url = "$ENDPOINT?query=${luceneQuery(title, artist).encodeURLQueryComponent()}&fmt=json&limit=$SEARCH_LIMIT"

        val response =
            try {
                gate.run { http.get(url, mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ProviderResult.TemporaryError(e::class.simpleName ?: "network error")
            }
        return parse(response.status, response.body, title, artist)
    }

    companion object {
        private const val ENDPOINT = "https://musicbrainz.org/ws/2/recording"
        const val MIN_INTERVAL_MILLIS = 1_100L
        private const val SEARCH_LIMIT = 10
        const val MIN_SCORE = 90
        const val MAX_MATCHES = 3

        /** MusicBrainz asks for an identifying agent with a way to reach the author. */
        const val USER_AGENT = "SimpMusic-GenrePlaylists/1.0 ( https://github.com/tlenenao/SimpMusic )"

        private val json = Json { ignoreUnknownKeys = true }

        private fun escape(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

        fun luceneQuery(
            title: String,
            artist: String,
        ) = "recording:\"${escape(title)}\" AND artist:\"${escape(artist)}\""

        private fun artistMatches(
            credit: String,
            wanted: String,
        ): Boolean {
            val a = TrackTextCleaner.comparable(credit).removePrefix("the")
            val b = TrackTextCleaner.comparable(wanted).removePrefix("the")
            if (a.isEmpty() || b.isEmpty()) return false
            return a == b || (minOf(a.length, b.length) >= 5 && (a.contains(b) || b.contains(a)))
        }

        fun parse(
            status: Int,
            body: String,
            title: String,
            artist: String,
        ): ProviderResult {
            // 503 is how MusicBrainz says "slow down"; 429 is the same thing from a proxy.
            if (status == 429 || status >= 500) return ProviderResult.TemporaryError("HTTP $status")
            // Any other 4xx is our request being refused; asking again would be refused again.
            if (status !in 200..299) return ProviderResult.NoMatch
            val recordings =
                runCatching { json.parseToJsonElement(body).jsonObject["recordings"]?.jsonArray }.getOrNull()
                    ?: return ProviderResult.TemporaryError("Unreadable response")

            val wantedTitle = TrackTextCleaner.comparable(title)
            val matches =
                recordings
                    .mapNotNull { it as? JsonObject }
                    .filter { rec ->
                        val score = rec["score"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val recTitle = rec["title"]?.jsonPrimitive?.content.orEmpty()
                        score >= MIN_SCORE &&
                            TrackTextCleaner.comparable(recTitle) == wantedTitle &&
                            creditedArtists(rec).any { artistMatches(it, artist) } &&
                            // The same track appears once per release and most copies carry no tags:
                            // take the MAX_MATCHES that have some, not the first ones.
                            (namedCounts(rec["genres"]) + namedCounts(rec["tags"])).isNotEmpty()
                    }.take(MAX_MATCHES)
            if (matches.isEmpty()) return ProviderResult.NoMatch

            val votes = linkedMapOf<String, Int>()
            for (rec in matches) {
                val list = namedCounts(rec["genres"]).ifEmpty { namedCounts(rec["tags"]) }
                for ((name, count) in list) if (count > 0) votes.merge(name, count, Int::plus)
            }
            val top = votes.values.maxOrNull() ?: return ProviderResult.NoMatch
            return ProviderResult.Tags(votes.map { (name, count) -> RawGenreTag(name, count * 100 / top) })
        }

        private fun creditedArtists(rec: JsonObject): List<String> =
            (rec["artist-credit"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.flatMap { credit ->
                listOfNotNull(
                    credit["name"]?.jsonPrimitive?.content,
                    (credit["artist"] as? JsonObject)?.get("name")?.jsonPrimitive?.content,
                )
            }

        private fun namedCounts(element: kotlinx.serialization.json.JsonElement?): List<Pair<String, Int>> =
            (element as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { tag ->
                val name = tag["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                name to (tag["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1)
            }
    }
}
