package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(PartVectors::class.java)

/**
 * The text of every part of every work read, encoded window by window in the background: a vector for every few hundred
 * words, so a chapter is a set of vectors and one scene unlike the rest is not averaged away. And paragraph by
 * paragraph ([Paragraphs]): what the tags are held against to be offered by the work's own text, a paragraph quoted
 * beside its tag. A part read again with another text is encoded again.
 */
@Service
class PartVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val workload: Workload,
) {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "part-vectors").apply { isDaemon = true } }
    private val wake = Object()

    @Volatile private var stopping = false

    @Volatile private var busy = false

    fun running(): Boolean = busy

    @EventListener(PartsSaved::class) fun partsSaved() = synchronized(wake) { wake.notifyAll() }

    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        worker.execute {
            while (!stopping) {
                if (pending() > 0) encodeWaiting()
                busy = false
                try {
                    synchronized(wake) { wake.wait(TimeUnit.MINUTES.toMillis(WAKE_MINUTES)) }
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
        }
    }

    /** Every part waiting encoded in one work of the queue of the computations, not a batch apart. */
    // The worker's loop: whatever fails is told in the log, and the parts are looked for again on the next wake
    @Suppress("TooGenericExceptionCaught")
    private fun encodeWaiting() {
        try {
            workload.run(Work("partVectors"), mapOf("parts" to 1)) {
                val reached = Reached()
                while (!stopping && encodeSome(reached) > 0) continue
            }
        } catch (e: Exception) {
            log.error("parts were not encoded", e)
        }
    }

    /**
     * Of one run, the last part of every source given to encode, of its windows and of its paragraphs: the next batch
     * looked for past it. A part left unencoded is met again on the next run.
     */
    private class Reached {
        val windows = HashMap<String, Pair<String, String>>()
        val paragraphs = HashMap<String, Pair<String, String>>()
    }

    /**
     * Encodes a batch of parts of every source, their windows and then their paragraphs, from past those [reached];
     * returns how many.
     */
    private fun encodeSome(reached: Reached): Int {
        val encoder = plugins.textEncoder() ?: return 0
        var done = 0
        stores.sources.forEach { s ->
            val todo = s.parts.windows.toEncode(encoder.id, BATCH, reached.windows[s.id])
            if (todo.isEmpty()) return@forEach
            reached.windows[s.id] = todo.last().place
            busy = true
            val windows = encoder.encodeWindows(todo.map { it.content })
            todo.forEachIndexed { i, part ->
                s.parts.windows.saveVectors(part.itemId, part.partId, encoder.id, part.hash, windows[i])
            }
            // The works searched by the meaning of their chapters' windows: indexed again with them
            events.publishEvent(TextVectorsChanged(s.id, todo.map { it.itemId }.distinct()))
            done += todo.size
        }
        stores.sources.forEach { s ->
            val todo =
                s.parts.paragraphs.paragraphsToEncode(Paragraphs.keyOf(encoder.id), BATCH, reached.paragraphs[s.id])
            if (todo.isEmpty()) return@forEach
            reached.paragraphs[s.id] = todo.last().place
            busy = true
            // The paragraphs of every part of the batch encoded together, then given back to their parts: a chapter's
            // few dozen alone ran the graph's batches half empty
            val texts = todo.map { Paragraphs.of(it.content) }
            val vectors = texts.flatten().chunked(PARAGRAPHS).flatMap { encoder.encode(it) }
            var from = 0
            todo.forEachIndexed { i, part ->
                val own = texts[i]
                s.parts.paragraphs.saveParagraphVectors(
                    part.itemId,
                    part.partId,
                    Paragraphs.keyOf(encoder.id),
                    part.hash,
                    vectors.subList(from, from + own.size),
                    own.map(Vectors::hash),
                )
                from += own.size
            }
            done += todo.size
        }
        return done
    }

    fun pending(): Int =
        plugins.textEncoder()?.let { e ->
            stores.sources.sumOf {
                it.parts.windows.countToEncode(e.id) +
                    it.parts.paragraphs.countParagraphsToEncode(Paragraphs.keyOf(e.id))
            }
        } ?: 0

    fun total(): Int = stores.sources.sumOf { it.parts.countWithText() }

    @PreDestroy
    fun shutdown() {
        stopping = true
        worker.shutdownNow()
    }

    companion object {
        /** Parts per encoder call: a chapter is tens of windows already. */
        private const val BATCH = 8

        /** Paragraphs per encoder call: a chapter is hundreds of them. */
        private const val PARAGRAPHS = 256

        /** Its step of the start: after the texts' vectors begin (3), before the sets' vectors (6). */
        private const val START_ORDER = 5

        /** The longest sleep of the worker between two looks for parts to encode: a part saved wakes it at once. */
        private const val WAKE_MINUTES = 10L
    }
}
