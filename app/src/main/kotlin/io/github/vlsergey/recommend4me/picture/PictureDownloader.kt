package io.github.vlsergey.recommend4me.picture

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A picture download that failed; [status], the HTTP status the host answered with, if it answered; [cause], what
 * failed under it, if anything did.
 */
class PictureException(message: String, val status: Int? = null, cause: Throwable? = null) : IOException(message, cause)

/**
 * Downloads pictures from the image hosts of the sites: no throttle (a CDN), the headers the source asks for, and a
 * body that stalls cut after [MINUTES] — a CDN may give each connection 120 KB/s, and an original of a few megabytes
 * takes a minute or two.
 */
object PictureDownloader {

    private val http: HttpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
            .build()

    private const val MINUTES = 5L

    /** The wait for the host to take the connection. */
    private const val CONNECT_SECONDS = 20L

    /** The wait for the host to answer a request. */
    private const val ANSWER_SECONDS = 60L

    /**
     * The bytes and the content type. Whatever fails — an address no request can be made of, a host that does not
     * answer, an answer that is no picture — is a [PictureException].
     */
    fun fetch(url: String, headers: Map<String, String>): Pair<ByteArray, String> {
        val response = response(url, headers)
        val status = response.statusCode()
        val type = response.headers().firstValue("Content-Type").orElse("application/octet-stream")
        val refused =
            when {
                status != HttpURLConnection.HTTP_OK -> PictureException("HTTP $status from $url", status)
                !type.startsWith("image/") -> PictureException("$url is $type, not an image")
                else -> null
            }
        if (refused != null) throw refused
        return response.body() to type.substringBefore(';').trim()
    }

    /** The host's answer to the request of the picture, its whole body read in [MINUTES] at most. */
    private fun response(url: String, headers: Map<String, String>): HttpResponse<ByteArray> {
        val pending = http.sendAsync(request(url, headers), HttpResponse.BodyHandlers.ofByteArray())
        return try {
            pending.get(MINUTES, TimeUnit.MINUTES)
        } catch (e: TimeoutException) {
            pending.cancel(true)
            throw PictureException("$url is not downloaded in $MINUTES minutes", cause = e)
        } catch (e: ExecutionException) {
            val failed = e.cause ?: e
            throw failed as? PictureException
                ?: PictureException(failed.message ?: failed.javaClass.simpleName, cause = failed)
        }
    }

    /** The request of the picture with the headers the source asks for; an address no request is made of refused. */
    private fun request(url: String, headers: Map<String, String>): HttpRequest =
        try {
            val builder =
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(ANSWER_SECONDS))
                    .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif;q=0.9")
            if ("User-Agent" !in headers) builder.header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.GET().build()
        } catch (e: IllegalArgumentException) {
            throw PictureException(e.message ?: e.javaClass.simpleName, cause = e)
        }

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
}
