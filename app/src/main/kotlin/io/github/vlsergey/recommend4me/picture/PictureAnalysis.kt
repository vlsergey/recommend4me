package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.encoder.ImageEncoder
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.Stores
import jakarta.annotation.PreDestroy
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(PictureAnalysis::class.java)

/**
 * THE ANALYSIS OF THE PICTURES in the background, without end: the pictures of every source ([NextPictures]) downloaded
 * and prepared on the threads of the downloads ([PictureFetching]), analysed by the picture encoder from the ORIGINAL
 * and kept ([PictureBatch]); the models of a content type retrained as the vectors come.
 */
@Component
class PictureAnalysis(
    private val stores: Stores,
    private val plugins: Plugins,
    contexts: SourceContexts,
    private val recommendations: Recommendations,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pictures").apply { isDaemon = true } }
    private val downloads =
        Executors.newFixedThreadPool(PARALLEL_DOWNLOADS) { r ->
            Thread(r, "picture-download").apply { isDaemon = true }
        }
    private val next = NextPictures(stores)
    private val fetching = PictureFetching(contexts)
    private val wake = Object()

    @Volatile private var analysing = false

    @Volatile private var stopping = false

    private val doneSinceStart = AtomicInteger()
    private var startedAt = 0L

    /** Whether pictures are being analysed now. */
    val running: Boolean
        get() = analysing

    /** The pictures analysed a minute since the analysis started; none in its first minute. */
    val perMinute: Double?
        get() {
            val minutes = (System.currentTimeMillis() - startedAt) / MILLIS_PER_MINUTE
            return if (startedAt > 0 && minutes > 1) doneSinceStart.get() / minutes else null
        }

    /**
     * The downloads streaming to the encoder: those ready, those in flight (source, item, position), and the new
     * vectors of every content type since its models were retrained.
     */
    private class Stream(val ready: ExecutorCompletionService<Fetched>) {
        val inFlight = HashSet<Pair<String, Pair<String, Int>>>()
        val sinceRetrain = HashMap<String, Int>()
    }

    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        worker.execute {
            try {
                stores.sources.forEach { s ->
                    plugins.imageEncoder()?.let { encoder ->
                        s.pictures.itemVectors
                            .itemsWithoutVectors(encoder.id)
                            .takeIf { it.isNotEmpty() }
                            ?.let { ids ->
                                s.pictures.itemVectors.refreshItemVectors(ids, encoder.id)
                                log.info("{}: vectors of the pictures of {} items made", s.id, ids.size)
                            }
                    }
                }
                startedAt = System.currentTimeMillis()
                loop()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // The boundary of the background thread: whatever stops the analysis is told, not lost on a thread
                // nobody waits for
                log.error("the picture analysis stopped", e)
            }
        }
    }

    /**
     * The downloads stream: up to [WINDOW] pictures are being downloaded and decoded at any time, and the encoder takes
     * whatever is ready, up to [BATCH] at once. No batch waits for its slowest picture.
     */
    private fun loop() {
        val stream = Stream(ExecutorCompletionService(downloads))
        while (!stopping) {
            val encoder = plugins.imageEncoder()
            if (encoder == null) {
                analysing = false
                synchronized(wake) { wake.wait(IDLE_MILLIS) }
            } else {
                if (stream.inFlight.size <= WINDOW - BATCH) {
                    next.of(encoder, WINDOW - stream.inFlight.size, stream.inFlight).forEach { job ->
                        stream.inFlight += job.key
                        stream.ready.submit { fetching.fetch(job, encoder) }
                    }
                }
                if (stream.inFlight.isEmpty()) idle(stream) else analyseReady(stream, encoder)
            }
        }
    }

    /**
     * Nothing in flight: the models of what got new vectors retrained, and a wait for new pictures, or till the first
     * failed one is due to be tried again.
     */
    private fun idle(stream: Stream) {
        analysing = false
        stream.sinceRetrain
            .filterValues { it > 0 }
            .keys
            .forEach { recommendations.scheduleRetrain(it, "pictures", byUser = false) }
        stream.sinceRetrain.clear()
        // Woken by new pictures, or when the first failed one is due to be tried again
        val retry = stores.sources.mapNotNull { it.pictures.queue.nextRetry() }.minOrNull()
        val wait =
            retry?.let { (it.toEpochMilli() - System.currentTimeMillis()).coerceIn(1, IDLE_MILLIS) } ?: IDLE_MILLIS
        synchronized(wake) { wake.wait(wait) }
    }

    /** The pictures ready analysed and kept; the models retrained every [RETRAIN_EVERY] new vectors. */
    private fun analyseReady(stream: Stream, encoder: ImageEncoder) {
        analysing = true
        val batch = gather(stream)
        if (batch.isEmpty()) return
        val done = PictureBatch.analyse(batch, encoder)
        doneSinceStart.addAndGet(done.analysed)
        done.made.forEach { (type, n) -> stream.sinceRetrain.merge(type, n, Int::plus) }
        batch.forEach { stream.inFlight -= it.job.key }
        retrainDue(stream.sinceRetrain)
    }

    /**
     * The downloads ready: the first one waited for, then — a fuller batch is cheaper for the encoder — the others
     * gathered for a few seconds more, up to [BATCH].
     */
    private fun gather(stream: Stream): List<Fetched> {
        val batch = ArrayList<Fetched>(BATCH)
        stream.ready.poll(WAIT_SECONDS, TimeUnit.SECONDS)?.let { batch += it.get() }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(GATHER_SECONDS)
        while (batch.isNotEmpty() && batch.size < BATCH && batch.size < stream.inFlight.size) {
            val left = until - System.nanoTime()
            val ready = if (left > 0) stream.ready.poll(left, TimeUnit.NANOSECONDS) else stream.ready.poll()
            batch += (ready ?: break).get()
        }
        return batch
    }

    /** The model learns from the pictures as they come, not once at the end of days. */
    private fun retrainDue(sinceRetrain: MutableMap<String, Int>) {
        sinceRetrain
            .filterValues { it >= RETRAIN_EVERY }
            .keys
            .forEach {
                recommendations.scheduleRetrain(it, "pictures", byUser = false)
                sinceRetrain[it] = 0
            }
    }

    /** There may be work: the loop looks at the queue again. */
    fun signal() = synchronized(wake) { wake.notifyAll() }

    @EventListener(PicturesListed::class) fun picturesListed() = signal()

    @PreDestroy
    fun shutdown() {
        stopping = true
        signal()
        worker.shutdownNow()
        downloads.shutdownNow()
        worker.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        /**
         * Its place among the steps of the start: after the vectors of the texts (3), before those of the parts (5).
         */
        const val START_ORDER = 4

        /** Pictures per encoder run at most. */
        const val BATCH = 64

        /** Pictures downloading or downloaded and waiting for the encoder, at most. */
        const val WINDOW = 2 * BATCH

        const val WAIT_SECONDS = 30L
        const val GATHER_SECONDS = 10L
        const val PARALLEL_DOWNLOADS = 24
        const val IDLE_MILLIS = 10 * 60_000L

        /** The wait for the worker to stop as the application stops. */
        const val SHUTDOWN_SECONDS = 5L

        /** The milliseconds of a minute: a unit. */
        const val MILLIS_PER_MINUTE = 60_000.0

        /** New picture vectors between retrainings. */
        const val RETRAIN_EVERY = 1000
    }
}
