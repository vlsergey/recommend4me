package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.background.Background
import io.github.vlsergey.recommend4me.background.Later
import io.github.vlsergey.recommend4me.background.Started
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.retry.Backoff
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.Stores
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(PictureDownloads::class.java)

/**
 * THE PICTURES OF THE WORKS DOWNLOADED — AN OUTER BATCH, NOT A LOOP (owner, 2026-11-19: "no loop in the background";
 * reading a site is an outer batch work making an event, which leads to the rest): begun by a site's page listing its
 * pictures ([SiteItemPictureListEvent]), a grade that keeps a work's originals ([begin]), the start when a picture
 * waits; it downloads while some picture is to be downloaded — every picture of the graded works first, then from the
 * top of the recommendations down ([NextPictures]), [WINDOW] at a time side by side — and ends. Every picture
 * downloaded is kept on disk, its preview and its original, and told ([SiteItemPictureDownloadEvent]): the analysis is
 * a work of the queue ([PictureVectors]). A download that failed is tried again a second after, then twice as late
 * every time, without end (user, 2026-10-21): the batch begun again at the first such time, once — no thread waiting
 * meanwhile.
 */
@Component
class PictureDownloads(
    private val stores: Stores,
    private val plugins: Plugins,
    contexts: SourceContexts,
    private val background: Background,
    private val events: ApplicationEventPublisher,
) {
    private val pool =
        Executors.newFixedThreadPool(PARALLEL) { r -> Thread(r, "picture-download").apply { isDaemon = true } }
    private val next = NextPictures(stores)
    private val fetching = PictureFetching(contexts)
    private val guard = Any()

    @Volatile private var batch: Started? = null

    /** Asked again while the batch runs: it looks again once it would end. */
    @Volatile private var askedAgain = false

    @Volatile private var stopping = false

    @Volatile private var retry: Later? = null

    /** Whether pictures are being downloaded. */
    val running: Boolean
        get() = batch != null

    /** A site's page listed pictures of its works. */
    @EventListener(SiteItemPictureListEvent::class) fun listed() = begin()

    /** A picture waits to be downloaded — what the data say: the batch begun. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val encoder = plugins.imageEncoder() ?: return
        if (stores.sources.any { it.pictures.queue.itemsWithPending(encoder.id).isNotEmpty() }) begin()
    }

    /** The batch begun, unless it runs: then it looks for pictures again before it ends. */
    fun begin() =
        synchronized(guard) {
            when {
                stopping -> Unit
                batch != null -> askedAgain = true
                else -> batch = background.start("pictures") { downloadAll() }
            }
        }

    // A BOUNDARY: the batch runs on a thread of its own, and whatever stops it is told, not lost on it
    @Suppress("TooGenericExceptionCaught")
    private fun downloadAll() {
        try {
            do {
                askedAgain = false
                while (!stopping && round()) continue
            } while (askedAgain && !stopping)
        } catch (e: Exception) {
            log.error("the pictures' downloads stopped", e)
        } finally {
            synchronized(guard) { batch = null }
            retryLater()
        }
    }

    /**
     * Up to [WINDOW] pictures downloaded side by side and kept, those of each source told; false when none was left.
     */
    private fun round(): Boolean {
        val jobs = plugins.imageEncoder()?.let { next.of(it, WINDOW, emptySet()) }.orEmpty()
        if (jobs.isEmpty()) return false
        val fetched = jobs.map { job -> pool.submit<Fetched> { fetching.fetch(job) } }.map { it.get() }
        val now = Instant.now()
        fetched.forEach { f -> if (f.original == null) failed(f, now) else kept(f) }
        fetched
            .filter { it.original != null }
            .groupBy({ it.job.store.id }, { it.job.picture.itemId })
            .forEach { (source, ids) -> events.publishEvent(SiteItemPictureDownloadEvent(source, ids.distinct())) }
        return true
    }

    /**
     * The picture's files written: its preview, and its original — kept till it is analysed, or as its work keeps it.
     */
    private fun kept(f: Fetched) {
        val store = f.job.store
        val p = f.job.picture
        val previewFile =
            p.previewFile
                ?: f.preview?.let { (bytes, type) -> store.files.save(p.itemId, "${p.position}-preview", bytes, type) }
        val (bytes, type) = f.original!!
        store.pictures.queue.saveDownloaded(
            p.itemId,
            p.position,
            previewFile,
            store.files.save(p.itemId, "${p.position}", bytes, type),
        )
    }

    /** The download failed: tried again later, never of a picture gone. */
    private fun failed(f: Fetched, now: Instant) {
        val p = f.job.picture
        val failures = p.failures + 1
        val at = if (f.lasting) null else RETRIES.nextAttempt(now, failures)
        f.job.store.pictures.queue.saveError(p.itemId, p.position, f.error ?: "no picture", failures, at)
    }

    /** The batch begun again at the first time a failed download is to be tried again, once; none of none. */
    private fun retryLater() {
        val at = stores.sources.mapNotNull { it.pictures.queue.nextRetry() }.minOrNull() ?: return
        val wait = Duration.between(Instant.now(), at)
        if (wait.isNegative || stopping) return
        synchronized(guard) {
            retry?.cancel()
            retry = background.later("pictures-retry", wait) { begin() }
        }
    }

    @PreDestroy
    fun shutdown() {
        synchronized(guard) { stopping = true }
        retry?.cancel()
        pool.shutdownNow()
        batch?.await(Duration.ofSeconds(SHUTDOWN_SECONDS))
    }

    private companion object {
        /** Pictures downloading side by side at most. */
        const val PARALLEL = 24

        /** Pictures of one round: downloaded side by side, kept, told. */
        const val WINDOW = 128

        /** The wait for the batch to stop as the application stops. */
        const val SHUTDOWN_SECONDS = 5L

        /** A failed download tried again a second after, then twice as late every time (user, 2026-10-21). */
        val RETRIES = Backoff(first = Duration.ofSeconds(1), factor = 2.0)
    }
}
