package io.github.vlsergey.recommend4me.request

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private val log = LoggerFactory.getLogger(SlowRequests::class.java)

/**
 * THE REQUESTS ANSWERED SLOWLY, in a log of their own (`slow-requests.log` in the data folder, and in the application's
 * log): every request of the browser that took [slow] or longer from its arrival to its answer — its method, its
 * address with the query, the status answered, how long it took and how many requests were being served at once when it
 * came, the others it may have waited behind. Timed after the guard of access: a refused request is not served.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class SlowRequests(@Value("\${recommend4me.slow-request:500ms}") private val slow: Duration) : OncePerRequestFilter() {

    /** The requests being served now. */
    private val serving = AtomicInteger()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val alongside = serving.getAndIncrement()
        val start = System.nanoTime()
        try {
            chain.doFilter(request, response)
        } finally {
            serving.decrementAndGet()
            val took = Duration.ofNanos(System.nanoTime() - start)
            if (took >= slow) {
                val query = request.queryString?.let { "?$it" }.orEmpty()
                log.info(
                    "{} {}{} → {} in {} ms, {} more served at once",
                    request.method,
                    request.requestURI,
                    query,
                    response.status,
                    took.toMillis(),
                    alongside,
                )
            }
        }
    }
}
