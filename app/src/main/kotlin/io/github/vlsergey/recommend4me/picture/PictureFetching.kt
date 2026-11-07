package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.encoder.ImageEncoder
import io.github.vlsergey.recommend4me.encoder.PreparedPicture
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceStore
import java.net.HttpURLConnection
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(PictureFetching::class.java)

/** A picture of the queue to download: of which source, and whether its original is kept. */
internal class PictureJob(val store: SourceStore, val picture: Picture, val keep: Boolean) {
    /** The picture among those of every source: the source, the item, the position. */
    val key: Pair<String, Pair<String, Int>>
        get() = store.id to (picture.itemId to picture.position)
}

/** A picture of the queue downloaded, and decoded and prepared for the encoder when it is to be analysed. */
internal class Fetched(
    val job: PictureJob,
    val original: Pair<ByteArray, String>?,
    val preview: Pair<ByteArray, String>?,
    val prepared: PreparedPicture?,
    val error: String?,
    /** The error does not pass: the picture is gone (404) or cannot be decoded — never tried again. */
    val lasting: Boolean = false,
)

/**
 * The download of one picture of the queue, on a thread of the downloads: the original from the first of its addresses
 * that gives it, the preview, and — when the picture is to be analysed — the original decoded and prepared, which
 * leaves the encoder's thread to the encoder.
 */
internal class PictureFetching(private val contexts: SourceContexts) {

    /** The original of a picture from one of its addresses, and the address; or the last failure, of none. */
    private class Original(val bytes: Pair<ByteArray, String>?, val address: String?, val error: Exception?)

    /** The original, the preview and what the encoder needs of the picture; a failed download its error. */
    fun fetch(job: PictureJob, encoder: ImageEncoder): Fetched {
        val original = original(job)
        val bytes = original.bytes
        return if (bytes == null) {
            val error = original.error?.let { it.message ?: it.javaClass.simpleName } ?: "no address"
            val gone = (original.error as? PictureException)?.status == HttpURLConnection.HTTP_NOT_FOUND
            Fetched(job, null, null, null, error, lasting = gone)
        } else {
            val preview = preview(job, bytes, original.address)
            if (job.picture.analyzed) Fetched(job, bytes, preview, null, null)
            else prepared(job, bytes, preview, encoder)
        }
    }

    /** The headers the source asks for to download the picture at [url]. */
    private fun headers(job: PictureJob, url: String) = job.store.source.pictureHeaders(url, contexts.of(job.store.id))

    /** The original from the first of the picture's addresses that gives it. */
    @Suppress("TooGenericExceptionCaught")
    private fun original(job: PictureJob): Original {
        var lastError: Exception? = null
        for (address in job.store.source.pictureAddresses(job.picture.url)) {
            try {
                return Original(PictureDownloader.fetch(address, headers(job, address)), address, null)
            } catch (e: Exception) {
                // A boundary: the addresses are tried in turn, and whatever fails at one — the source's plugin making
                // its headers, the request, the host — leaves it to the next
                lastError = e
            }
        }
        return Original(null, null, lastError)
    }

    /**
     * The preview to keep: none when one is on disk, the [original] when it came from the picture's own address, else
     * the picture's own address downloaded — the original standing for it when that fails.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun preview(job: PictureJob, original: Pair<ByteArray, String>, used: String?): Pair<ByteArray, String>? {
        val picture = job.picture
        return try {
            when {
                picture.previewFile != null -> null
                used == picture.url -> original
                else -> PictureDownloader.fetch(picture.url, headers(job, picture.url))
            }
        } catch (e: Exception) {
            // A boundary: whatever fails — the source's plugin making the headers, the request, the host — the
            // original is the preview
            log.debug("preview {} was not downloaded, the original stands for it: {}", picture.url, e.message)
            original
        }
    }

    /** The original decoded and prepared for the encoder; a picture no reader decodes a lasting error. */
    @Suppress("TooGenericExceptionCaught")
    private fun prepared(
        job: PictureJob,
        original: Pair<ByteArray, String>,
        preview: Pair<ByteArray, String>?,
        encoder: ImageEncoder,
    ): Fetched {
        val prepared =
            try {
                PictureFiles.decode(original.first)?.let(encoder::prepare)
            } catch (e: Exception) {
                // A boundary: bytes of the web go through the readers of the formats and the encoder's preparing, and
                // a malformed picture may fail them in any way — it is a picture that cannot be decoded
                return Fetched(
                    job,
                    original,
                    preview,
                    null,
                    "cannot decode ${original.second}: ${e.message ?: e.javaClass.simpleName}",
                    lasting = true,
                )
            }
        return if (prepared == null)
            Fetched(job, original, preview, null, "cannot decode ${original.second}: no reader", lasting = true)
        else Fetched(job, original, preview, prepared, null)
    }
}
