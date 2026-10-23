package com.maxrave.data.genre

import com.maxrave.ktorext.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

internal data class GenreHttpResponse(
    val status: Int,
    val body: String,
)

/** The one HTTP call the genre providers need. Throws on network failure; a status is a response. */
internal fun interface GenreHttpGet {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): GenreHttpResponse
}

internal class KtorGenreHttpGet : GenreHttpGet {
    private val client by lazy {
        HttpClient(getEngine()) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
        }
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): GenreHttpResponse {
        val response = client.get(url) { headers.forEach { (k, v) -> header(k, v) } }
        return GenreHttpResponse(response.status.value, response.bodyAsText())
    }
}

/**
 * Keeps successive calls at least [intervalMillis] apart, process-wide for the instance.
 * MusicBrainz asks for at most one request per second; this is what enforces it.
 */
internal class MinIntervalGate(
    private val intervalMillis: Long,
    private val elapsedMillis: () -> Long = monotonicMillis(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var lastAt: Long? = null

    suspend fun <T> run(block: suspend () -> T): T =
        mutex.withLock {
            lastAt?.let { last ->
                val wait = intervalMillis - (elapsedMillis() - last)
                if (wait > 0) sleep(wait)
            }
            try {
                block()
            } finally {
                // Stamped after the call, so a slow response does not shorten the gap that follows.
                lastAt = elapsedMillis()
            }
        }

    companion object {
        private fun monotonicMillis(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
