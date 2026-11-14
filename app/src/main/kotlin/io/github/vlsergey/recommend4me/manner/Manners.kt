package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.part.PartMark
import io.github.vlsergey.recommend4me.part.PartsSaved
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(Manners::class.java)

/** The manners of this many items of a content type were made again: the model may want to learn them. */
data class MannersChanged(val type: String, val items: Int)

/**
 * THE MANNER OF EVERY ITEM WITH A TEXT, made in the queue of the computations after the chapters read go quiet, and on
 * start: the words of the content type ([MannerWords]) fitted on every text of it — again whenever the number of the
 * items with a text changes, every manner then made again by them, as the directions of the sets are ([SetDirections]
 * [io.github.vlsergey.recommend4me.setvector.SetDirections]) —, else the manner of every item whose texts changed made
 * by the words kept. Two passes over every text to fit: the words chosen of the first, the shares of the second.
 */
@Service
class Manners(
    private val stores: Stores,
    private val workload: Workload,
    private val events: ApplicationEventPublisher,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "manners").apply { isDaemon = true }
    }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** Of every item of every source of the [type], its manner made again where its texts or the words changed. */
    fun refresh(type: TypeStore) {
        val started = System.currentTimeMillis()
        val texts = type.sources.associateWith { s -> s.parts.marks.of(null).mapValues { (_, parts) -> hashOf(parts) } }
        val count = texts.values.sumOf { it.size }
        val kept = type.mannerWords.find()
        val fitting = count > 0 && (kept == null || kept.texts != count)
        val made =
            when {
                fitting -> fitted(type, texts, count)
                kept != null -> caughtUp(kept, texts)
                else -> 0
            }
        val gone = texts.entries.sumOf { (s, own) -> (s.manner.made().keys - own.keys).also(s.manner::remove).size }
        if (made + gone > 0) {
            events.publishEvent(MannersChanged(type.id, made + gone))
            log.info(
                "{}: the manners of {} items made{}, of {} taken away, of {} items with a text, in {} ms",
                type.id,
                made,
                if (fitting) " by words fitted again" else "",
                gone,
                count,
                System.currentTimeMillis() - started,
            )
        }
    }

    /**
     * The words fitted on every text of the type — [count] items with a text, of the hashes [texts] — and the manner of
     * every one made by them; how many were made.
     */
    private fun fitted(type: TypeStore, texts: Map<SourceStore, Map<String, String>>, count: Int): Int {
        val tally = WordTally()
        type.sources.forEach { s -> s.parts.texts.forEach { _, own -> tally.add(own) } }
        val chosen = tally.chosen(MannerWords.SIZE)
        // The shares of the chosen words in every text, the means and spreads not known yet: of a draft of them
        val draft = MannerWords(0, chosen, FloatArray(chosen.size), FloatArray(chosen.size) { 1f }, 0)
        val shares =
            type.sources.associateWith { s ->
                HashMap<String, FloatArray>().apply {
                    s.parts.texts.forEach { id, own -> draft.shares(own)?.let { put(id, it) } }
                }
            }
        val words =
            MannerWords.fitted(
                Vectors.keyOf("${type.id}:manner:${System.nanoTime()}"),
                chosen,
                shares.values.flatMap { it.values },
                count,
            )
        type.mannerWords.keep(words, Instant.now())
        shares.forEach { (s, own) ->
            own.forEach { (id, v) ->
                texts[s]?.get(id)?.let { hash -> s.manner.save(id, MannerMade(words.id, hash), words.standardised(v)) }
            }
        }
        return shares.values.sumOf { it.size }
    }

    /** The manner of every item whose [texts] are not those its manner was made of made by the [words]; how many. */
    private fun caughtUp(words: MannerWords, texts: Map<SourceStore, Map<String, String>>): Int =
        texts.entries.sumOf { (s, own) ->
            val made = s.manner.made()
            val todo = own.filter { (id, hash) -> made[id] != MannerMade(words.id, hash) }
            todo.keys.chunked(CHUNK).forEach { chunk ->
                s.parts.contentsOf(chunk).forEach { (id, parts) ->
                    words.of(parts.map { it.third })?.let {
                        s.manner.save(id, MannerMade(words.id, todo.getValue(id)), it)
                    }
                }
            }
            todo.size
        }

    // --- In the background ---

    @EventListener(ApplicationReadyEvent::class)
    fun start() = stores.types.forEach { schedule(it, START_DELAY_SECONDS, "start") }

    @EventListener fun partsSaved(event: PartsSaved) = schedule(stores.typeOf(event.source), QUIET_SECONDS, "parts")

    private fun schedule(type: TypeStore, delaySeconds: Long, reason: String) {
        val work = Work(MAKING, type = type.id)
        workload.plan(work, Instant.now().plusSeconds(delaySeconds), reason)
        val next =
            scheduler.schedule(
                {
                    // The boundary of the scheduler's thread: whatever the making throws is logged here, or the future
                    // it was put in would keep it unseen
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        workload.run(work) { refresh(type) }
                    } catch (e: Exception) {
                        log.warn("{}: the manners were not made: {}", type.id, e.message, e)
                    }
                },
                delaySeconds,
                TimeUnit.SECONDS,
            )
        pending.put(type.id, next)?.cancel(false)
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        const val MAKING = "manners"

        private const val START_DELAY_SECONDS = 60L

        /** The quiet after the last chapters read before the manners are made: a reader reads on. */
        private const val QUIET_SECONDS = 60L

        /** The items whose texts are read at once: memory, not meaning. */
        private const val CHUNK = 50

        /** What the texts of an item's parts are, of their hashes: its manner is made again when it changes. */
        fun hashOf(parts: List<PartMark>): String = Vectors.hash(parts.joinToString("\n") { "${it.partId}:${it.hash}" })
    }
}
