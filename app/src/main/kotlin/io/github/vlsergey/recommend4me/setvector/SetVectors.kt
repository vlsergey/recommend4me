package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.review.ReviewRepository
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.concurrent.Executors
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(SetVectors::class.java)

/**
 * The sets of every item as vectors of fixed length ([SetEmbedding]): its screenshots (picture vectors), its reviews (a
 * vector of every review, read whole) and the windows of its text, kept in its source database and made again, in the
 * background, when a set grows. The graded works first, then from the top of the recommendations down.
 *
 * THE DIRECTIONS ARE FITTED ONCE per content type, on a sample of its catalogue, and kept ([SetDirections]); the
 * members of the sets are read by [SetMembers].
 */
@Service
class SetVectors(
    private val stores: Stores,
    private val sets: SetMembers,
    private val directions: SetDirections,
    private val contexts: SourceContexts,
    private val events: ApplicationEventPublisher,
    private val workload: Workload,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "set-vectors").apply { isDaemon = true } }

    @Volatile private var stopping = false

    @Volatile private var busy = false

    fun running(): Boolean = busy

    /** How far the current pass has got: sets made, of the sets it found to make. */
    @Volatile private var doneNow = 0

    @Volatile private var totalNow = 0

    val done: Int
        get() = doneNow

    val total: Int
        get() = totalNow

    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        worker.execute {
            while (!stopping && refreshAll()) {
                busy = false
                try {
                    Thread.sleep(PAUSE.toMillis())
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
        }
    }

    /** One pass over every type; false when the worker was interrupted, to stop. */
    // The worker's loop: whatever fails is told in the log, and the next pass tries again
    @Suppress("TooGenericExceptionCaught")
    private fun refreshAll(): Boolean =
        try {
            doneNow = 0
            totalNow = 0
            stores.types.forEach { refresh(it) }
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            log.error("set vectors were not made", e)
            true
        }

    /**
     * The sets of a normalized work of several [items], each made of the members of all of them by the directions in
     * use: the reviews of one work on two sites are one set. A review not encoded yet is encoded now: for a
     * computation, not a request.
     */
    fun ofWork(type: TypeStore, items: List<ItemKey>): Map<String, FloatArray> = ofWorks(type, listOf(items)).single()

    /**
     * [ofWork] of every work of [works] (each its items), the members of all of them read at once: a source's and a
     * kind's in one reading, not a reading a work.
     */
    fun ofWorks(type: TypeStore, works: List<List<ItemKey>>): List<Map<String, FloatArray>> {
        val bySource = works.flatten().groupBy({ it.source }, { it.id }).mapValues { it.value.distinct() }
        val out = List(works.size) { HashMap<String, FloatArray>() }
        SetKind.entries.forEach { kind ->
            val encoder = sets.encoderOf(kind)
            val used = encoder?.let { e -> type.embeddings.find(kind)?.takeIf { it.encoder == e } } ?: return@forEach
            val members = bySource.mapValues { (source, ids) ->
                type.source(source)?.let { s -> sets.members(s, kind, used.encoder, ids) }.orEmpty()
            }
            works.forEachIndexed { w, items ->
                val all = items.flatMap { members[it.source]?.get(it.id).orEmpty() }
                used.embedding.of(all)?.let { out[w][kind.key] = it }
            }
        }
        return out
    }

    /** Fits the missing directions of a type, then makes the vectors of every item whose set grew. */
    fun refresh(type: TypeStore): Int {
        val pass = Pass(type.id)
        val stopped = SetKind.entries.any { kind -> refreshKind(type, kind, pass) }
        if (!stopped) pass.announceRest()
        return pass.made
    }

    /**
     * The vectors of the sets of [kind] made of every source of [type] whose sets grew, by the directions in use —
     * fitted first when missing; true when the worker is stopping, the pass to end.
     */
    private fun refreshKind(type: TypeStore, kind: SetKind, pass: Pass): Boolean {
        val encoder = sets.encoderOf(kind) ?: return false
        // The sizes of the sets read once, for the directions and for what grew alike: of the chapters, a join of
        // every window's row
        val sizes = type.sources.associateWith { sets.sizes(it, kind, encoder) }
        val embedding = directions.inUse(type, kind, encoder, sizes)
        return embedding != null &&
            type.sources.any { s -> refreshSource(s, kind, embedding, sizes[s].orEmpty(), pass) && stopping }
    }

    /**
     * The vectors of the sets of [kind] made of every item of [s] whose set grew — of the size [have] — whether there
     * was any.
     */
    private fun refreshSource(
        s: SourceStore,
        kind: SetKind,
        embedding: StoredEmbedding,
        have: Map<String, Int>,
        pass: Pass,
    ): Boolean {
        val stored = if (have.isEmpty()) emptyMap() else s.setVectors.sources(kind, embedding.id)
        val todoSet = have.filter { (id, n) -> n > 0 && stored[id] != n }.keys
        if (todoSet.isEmpty()) return false
        busy = true
        val todo = contexts.of(s.id).itemsInOrder().filter { it in todoSet }
        totalNow += todo.size
        log.info("{}: making {} vectors of {} items", s.id, kind.key, todo.size)
        workload.run(Work("setVectors", source = s.id, facet = kind.key), mapOf("setsGrew" to todo.size)) {
            for (ids in todo.chunked(CHUNK)) {
                if (stopping) break
                save(s, kind, embedding, have, ids)
                pass.count(ids.size)
            }
        }
        return true
    }

    /** The vectors of the sets of [kind] of the items [ids] made and kept, each with the size [have] of its set. */
    private fun save(
        s: SourceStore,
        kind: SetKind,
        embedding: StoredEmbedding,
        have: Map<String, Int>,
        ids: List<String>,
    ) {
        val made = sets.members(s, kind, embedding.encoder, ids)
        ids.forEach { id ->
            embedding.embedding.of(made[id].orEmpty())?.let {
                s.setVectors.save(id, kind, embedding.id, have.getValue(id), it)
            }
        }
    }

    /** One pass of the making of a type's vectors: how many were made, and those not told to the model yet. */
    private inner class Pass(private val type: String) {
        var made = 0
            private set

        private var unannounced = 0
        private var announcedAt = System.currentTimeMillis()

        /**
         * [n] vectors more made; the model learns the new vectors as they come, not once at the end of hours: told of
         * them every [ANNOUNCE_EVERY] or after [ANNOUNCE_AFTER].
         */
        fun count(n: Int) {
            made += n
            doneNow += n
            unannounced += n
            if (
                unannounced >= ANNOUNCE_EVERY || System.currentTimeMillis() - announcedAt >= ANNOUNCE_AFTER.toMillis()
            ) {
                events.publishEvent(SetVectorsChanged(type, unannounced))
                unannounced = 0
                announcedAt = System.currentTimeMillis()
            }
        }

        /** The vectors made since the model was last told, told at the end of the pass. */
        fun announceRest() {
            if (unannounced > 0) events.publishEvent(SetVectorsChanged(type, unannounced))
        }
    }

    @PreDestroy
    fun shutdown() {
        stopping = true
        worker.shutdownNow()
    }

    companion object {
        private const val CHUNK = 200

        /** Its step of the start: after the parts' vectors (5), before the jobs (10). */
        private const val START_ORDER = 6
        private const val ANNOUNCE_EVERY = 2_000
        private val ANNOUNCE_AFTER: Duration = Duration.ofMinutes(5)
        private val PAUSE: Duration = Duration.ofMinutes(10)

        /** A review as its set reads it. */
        fun textOf(review: String) = ReviewRepository.textOf(review)
    }
}
