package io.github.vlsergey.recommend4me.piece

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.encoder.PieceEncoder
import io.github.vlsergey.recommend4me.encoder.PieceProjection
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import java.time.Instant
import kotlin.random.Random
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(PieceProjections::class.java)

/** The projection of the pieces of the [type] was learnt anew: every piece of it is to be made again by it. */
data class PieceProjectionFitEvent(val type: String)

/**
 * THE PROJECTION OF THE PIECES' VECTORS AND THEIR CODES, of a type's own chapters: the leading [WIDTH] directions of
 * the vectors of the encoder's head of [PIECES] pieces of chapters drawn at random ([PieceDirections]), then the cuts
 * of every number projected ([PieceCodebook]) — learnt once of an encoder of pieces when none is kept
 * ([fitPieceProjection]), what the data say at a start and as the chapters of a type are written; learnt of other works
 * they lose little (0.015 of the MRR of 85 retellings, owner, 2026-11-25). Learnt anew, every piece is made anew
 * ([PieceProjectionFitEvent]). A type with too few chapters has none, and no pieces.
 */
@Service
class PieceProjections(
    private val stores: Stores,
    private val plugins: Plugins,
    private val tasks: Tasks,
    private val events: ApplicationEventPublisher,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(KIND) { left, reason -> left.work.type?.let { plan(it, reason) } }
    }

    /** The encoder of pieces of now, when its files are in place. */
    fun encoder(): PieceEncoder? = plugins.textEncoder()?.pieces?.takeIf { it.ready() }

    /** What the pieces of the [type] are made and read with now; null of no encoder of pieces, or of none learnt. */
    fun of(type: TypeStore): FittedPieces? = encoder()?.let { type.pieceProjections.of(it.id) }

    /** Of every type with chapters whose encoder of pieces has no projection kept, one learnt: what the data say. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val encoder = encoder() ?: return
        stores.types.filter { it.pieceProjections.of(encoder.id) == null }.forEach { plan(it.id, "encoder") }
    }

    /**
     * The chapters of works of a type written, of a type with no projection: one learnt of its chapters — of a type of
     * too few, nothing.
     */
    @EventListener
    fun chaptersWritten(event: WorkDocumentWriteEvent) {
        val type = stores.type(event.type)
        val encoder = encoder()
        if (WorkStage.WINDOWS in event.stages && type != null && encoder != null)
            if (type.pieceProjections.of(encoder.id) == null) plan(type.id, "parts")
    }

    private fun plan(typeId: String, reason: String) =
        tasks.plan(Task(KIND, type = typeId), null, reason) { fit(typeId) }

    /** The projection of the [typeId] learnt of its chapters drawn at random and kept; nothing of a type of none. */
    internal fun fit(typeId: String) {
        val type = stores.type(typeId)
        val encoder = encoder()
        if (type != null && encoder != null) fit(type, encoder)
    }

    /**
     * The head's vectors of [PIECES] pieces of the [type]'s chapters drawn at random, by the [encoder]: of every source
     * with chapters an equal share, of [TEXTS] of its chapters at most.
     */
    private fun sample(type: TypeStore, encoder: PieceEncoder): List<FloatArray> {
        val draw = Random(type.id.hashCode())
        val texts = type.sources.map { it.parts.windows.sampleTexts(TEXTS) }.filter { it.isNotEmpty() }
        val share = PIECES / texts.size.coerceAtLeast(1)
        return texts.flatMap { own ->
            val vectors = ArrayList<FloatArray>()
            own.chunked(BATCH).forEach { batch ->
                if (vectors.size < share)
                    encoder.encode(batch, null).forEach { e -> vectors += e.pieces.shuffled(draw).take(PER_TEXT) }
            }
            vectors.take(share)
        }
    }

    private fun fit(type: TypeStore, encoder: PieceEncoder) {
        val typeId = type.id
        val started = System.currentTimeMillis()
        val vectors = sample(type, encoder)
        if (vectors.size < LEAST) {
            log.info("{}: the projection of the pieces not learnt, of {} pieces of chapters", typeId, vectors.size)
            return
        }
        val rows = PieceDirections.leading(vectors.take(PIECES), WIDTH)
        val projected = vectors.map { v -> PieceDirections.unit(FloatArray(WIDTH) { k -> dot(rows[k], v) }) }
        val now = Instant.now()
        val fitted =
            FittedPieces(
                PieceProjection(now.toEpochMilli().toString(Character.MAX_RADIX), rows),
                PieceCodebook.learnt(projected),
                now,
                vectors.size,
            )
        type.pieceProjections.keep(encoder.id, fitted)
        log.info(
            "{}: the projection of the pieces learnt of {} pieces of chapters in {} ms",
            typeId,
            vectors.size,
            System.currentTimeMillis() - started,
        )
        events.publishEvent(PieceProjectionFitEvent(typeId))
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    companion object {
        private val KIND = TaskKind.FIT_PIECE_PROJECTION

        /** The numbers of a piece's vector projected. */
        const val WIDTH = 128

        /**
         * The pieces learnt of, the chapters of a source drawn for them, the pieces of a chapter at most, the least
         * learnt of.
         */
        private const val PIECES = 100_000
        private const val TEXTS = 600
        private const val PER_TEXT = 200
        private const val LEAST = 10_000

        /** The chapters a run of the encoder reads: memory, not meaning. */
        private const val BATCH = 8
    }
}
