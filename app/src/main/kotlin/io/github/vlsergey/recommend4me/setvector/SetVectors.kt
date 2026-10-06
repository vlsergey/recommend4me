package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.review.ReviewRepository
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.vector.Vectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger(SetVectors::class.java)

/**
 * The sets of every item as vectors of fixed length ([SetEmbedding]): its screenshots (picture
 * vectors), its reviews (a vector of every review, read whole) and the windows of its text, kept in
 * its source database and made again, in the background, when a set grows. The graded works first,
 * then from the top of the recommendations down.
 *
 * THE DIRECTIONS ARE FITTED ONCE per content type, on a sample of its catalogue, and kept: fitted
 * again, they would make every stored vector a vector of another space. They are fitted again only
 * when the encoder of the members changes; the vectors carry the id of the directions they are of.
 */
@Service
class SetVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val phrases: PhraseVectors,
    private val contexts: SourceContexts,
    private val events: ApplicationEventPublisher,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "set-vectors").apply { isDaemon = true } }

    @Volatile
    private var stopping = false

    @Volatile
    private var busy = false

    fun running(): Boolean = busy

    /** How far the current pass has got: sets made, of the sets it found to make. */
    @Volatile
    private var doneNow = 0

    @Volatile
    private var totalNow = 0

    val done: Int get() = doneNow
    val total: Int get() = totalNow

    @Order(6)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        worker.execute {
            while (!stopping) {
                try {
                    doneNow = 0
                    totalNow = 0
                    stores.types.forEach { refresh(it) }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                } catch (e: Exception) {
                    log.error("set vectors were not made", e)
                }
                busy = false
                try {
                    Thread.sleep(PAUSE.toMillis())
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
        }
    }

    /** The encoder the members of a kind of sets are encoded by. */
    private fun encoderOf(kind: SetKind): String? = when (kind) {
        SetKind.SCREENS -> plugins.imageEncoder()?.id
        SetKind.REVIEWS, SetKind.PARTS -> plugins.textEncoder()?.id
    }

    /** How many members every item's set of [kind] has now. */
    private fun sizes(s: SourceStore, kind: SetKind, encoder: String): Map<String, Int> = when (kind) {
        SetKind.SCREENS -> s.pictures.screenshotCounts(encoder)
        SetKind.REVIEWS -> s.reviews.counts()
        SetKind.PARTS -> s.parts.windowCounts(encoder)
    }

    private fun members(s: SourceStore, kind: SetKind, encoder: String, ids: List<String>): Map<String, List<FloatArray>> = when (kind) {
        SetKind.SCREENS -> s.pictures.screenshots(ids, encoder)
        SetKind.REVIEWS -> {
            val texts = s.reviews.textsOf(ids).mapValues { (_, list) -> list.distinct() }
            val encoded = phrases.of(s, texts.values.flatten().toSet())
            texts.mapValues { (_, list) -> list.mapNotNull { encoded[it] } }
        }
        SetKind.PARTS -> s.parts.windows(ids, encoder)
    }

    private fun sample(s: SourceStore, kind: SetKind, encoder: String, n: Int): List<FloatArray> = when (kind) {
        SetKind.SCREENS -> s.pictures.sample(n, encoder)
        SetKind.REVIEWS -> s.reviews.sample(n).distinct().let { texts -> phrases.of(s, texts).values.toList() }
        SetKind.PARTS -> s.parts.sample(n, encoder)
    }

    /** Fits the missing directions of a type, then makes the vectors of every item whose set grew. */
    fun refresh(type: TypeStore): Int {
        var made = 0
        var unannounced = 0
        var announcedAt = System.currentTimeMillis()
        for (kind in SetKind.entries) {
            val encoder = encoderOf(kind) ?: continue
            val embedding = embeddingOf(type, kind, encoder) ?: continue
            for (s in type.sources) {
                val have = sizes(s, kind, encoder)
                if (have.isEmpty()) continue
                val stored = s.setVectors.sources(kind, embedding.id)
                val todoSet = have.filter { (id, n) -> n > 0 && stored[id] != n }.keys
                if (todoSet.isEmpty()) continue
                busy = true
                val todo = contexts.of(s.id).itemsInOrder().filter { it in todoSet }
                totalNow += todo.size
                log.info("{}: making {} vectors of {} items", s.id, kind.key, todo.size)
                for (ids in todo.chunked(CHUNK)) {
                    if (stopping) return made
                    val sets = members(s, kind, encoder, ids)
                    ids.forEach { id -> embedding.embedding.of(sets[id].orEmpty())?.let { s.setVectors.save(id, kind, embedding.id, have.getValue(id), it) } }
                    made += ids.size
                    doneNow += ids.size
                    unannounced += ids.size
                    // The model learns the new vectors as they come, not once at the end of hours
                    if (unannounced >= ANNOUNCE_EVERY || System.currentTimeMillis() - announcedAt >= ANNOUNCE_AFTER.toMillis()) {
                        events.publishEvent(SetVectorsChanged(type.id, unannounced))
                        unannounced = 0
                        announcedAt = System.currentTimeMillis()
                    }
                }
            }
        }
        if (unannounced > 0) events.publishEvent(SetVectorsChanged(type.id, unannounced))
        return made
    }

    /** The directions of a kind in use, fitted now when there are none of [encoder] and enough of a catalogue to fit them on. */
    private fun embeddingOf(type: TypeStore, kind: SetKind, encoder: String): StoredEmbedding? {
        type.embeddings.find(kind)?.takeIf { it.encoder == encoder }?.let { return it }
        val sample = type.sources.flatMap { sample(it, kind, encoder, SAMPLE / type.sources.size) }
        if (sample.size < MIN_SAMPLE) return null
        val embedding = SetEmbedding.fit(sample, SetEmbedding.DIRECTIONS)
        val id = Vectors.keyOf("${type.id}:${kind.name}:$encoder:${System.nanoTime()}")
        type.embeddings.save(kind, id, encoder, embedding, Instant.now())
        log.info("{}: directions of {} fitted on {} members", type.id, kind.key, sample.size)
        return StoredEmbedding(id, encoder, embedding)
    }

    @PreDestroy
    fun shutdown() {
        stopping = true
        worker.shutdownNow()
    }

    companion object {
        private const val SAMPLE = 20_000
        private const val MIN_SAMPLE = 2_000
        private const val CHUNK = 200
        private const val ANNOUNCE_EVERY = 2_000
        private val ANNOUNCE_AFTER: Duration = Duration.ofMinutes(5)
        private val PAUSE: Duration = Duration.ofMinutes(10)

        /** A review as its set reads it. */
        fun textOf(review: String) = ReviewRepository.textOf(review)
    }
}
