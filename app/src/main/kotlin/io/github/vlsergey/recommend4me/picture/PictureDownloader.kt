package io.github.vlsergey.recommend4me.picture

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A picture download that failed; [status], the HTTP status the host answered with, if it answered. */
class PictureException(message: String, val status: Int? = null) : IOException(message)

/**
 * Downloads pictures from the image hosts of the sites: no throttle (a CDN), the headers the source
 * asks for, and a body that stalls cut after [MINUTES] — a CDN may give each connection 120 KB/s,
 * and an original of a few megabytes takes a minute or two.
 */
object PictureDownloader {

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private const val MINUTES = 5L

    /** The bytes and the content type. */
    fun fetch(url: String, headers: Map<String, String>): Pair<ByteArray, String> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif;q=0.9")
        if ("User-Agent" !in headers) builder.header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val pending = http.sendAsync(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        val response = try {
            pending.get(MINUTES, TimeUnit.MINUTES)
        } catch (e: TimeoutException) {
            pending.cancel(true)
            throw PictureException("$url is not downloaded in $MINUTES minutes")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
        if (response.statusCode() != 200) throw PictureException("HTTP ${response.statusCode()} from $url", response.statusCode())
        val type = response.headers().firstValue("Content-Type").orElse("application/octet-stream")
        if (!type.startsWith("image/")) throw PictureException("$url is $type, not an image")
        return response.body() to type.substringBefore(';').trim()
    }

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
}
