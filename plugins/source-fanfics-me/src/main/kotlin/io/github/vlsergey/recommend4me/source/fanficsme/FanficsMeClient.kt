package io.github.vlsergey.recommend4me.source.fanficsme

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(FanficsMeClient::class.java)

/**
 * The requests of fanfics.me: one at a time, [PAUSE] apart, saying what asks. A request the site
 * fails — an error of its own, a timeout — is asked again after a wait, [ATTEMPTS] times in all.
 */
class FanficsMeClient {
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()

    /** When the last request was sent: the next waits out [PAUSE] after it. */
    private var lastRequest: Instant = Instant.EPOCH

    /** The page at [url]; null when the site has none (404). */
    @Synchronized
    fun get(url: String): String? {
        var failure: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_WAIT.toMillis() * attempt)
            val wait = Duration.between(Instant.now(), lastRequest.plus(PAUSE))
            if (!wait.isNegative) Thread.sleep(wait.toMillis())
            try {
                val response = http.send(
                    HttpRequest.newBuilder(URI(url)).timeout(TIMEOUT).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                when (response.statusCode()) {
                    200 -> return response.body()
                    404 -> return null
                    else -> failure = IllegalStateException("the site answered ${response.statusCode()} to $url")
                }
            } catch (e: java.io.IOException) {
                failure = e
            } finally {
                lastRequest = Instant.now()
            }
            log.warn("{} (attempt {} of {})", failure!!.message, attempt + 1, ATTEMPTS)
        }
        throw failure!!
    }

    companion object {
        /** Says what asks: a personal recommender reading the site's lists. */
        const val USER_AGENT = "recommend4me-bot (a personal recommender)"

        private val PAUSE: Duration = Duration.ofSeconds(1)
        private val TIMEOUT: Duration = Duration.ofSeconds(120)
        private val RETRY_WAIT: Duration = Duration.ofSeconds(15)
        private const val ATTEMPTS = 3
    }
}
