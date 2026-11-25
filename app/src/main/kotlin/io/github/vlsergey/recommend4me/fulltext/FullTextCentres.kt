package io.github.vlsergey.recommend4me.fulltext

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import java.time.Instant
import java.util.Random
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(FullTextCentres::class.java)

/**
 * The centre of the windows' vectors of the [type] was learnt anew: every window's full-text vector is to be made again
 * by it.
 */
data class FullTextCentreFitEvent(val type: String)

/**
 * THE CENTRE OF THE WINDOWS' VECTORS OF A TYPE AND ITS RANDOM ROTATION, what every window's full-text vector is made by
 * ([FullTextVectors]): the mean of the windows of [CHAPTERS] chapters and [TEXTS] texts searched by meaning of every
 * source drawn at random, by the encoder — of the texts themselves, not of the vectors kept: a type is learnt of as its
 * texts are written, before any vector of them is made. Learnt once of an encoder of windows when none is kept
 * ([fitFullTextCentre]), what the data say at a start and as the texts or the chapters of a type are written; of fewer
 * than [LEAST] vectors nothing, and no window of the type is searched by meaning. Learnt anew, every window's full-text
 * vector is made anew ([FullTextCentreFitEvent]).
 */
@Service
class FullTextCentres(
    private val stores: Stores,
    private val plugins: Plugins,
    private val tasks: Tasks,
    private val events: ApplicationEventPublisher,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(KIND) { left, reason -> left.work.type?.let { plan(it, reason) } }
    }

    /** The encoder of the windows of now, when its files are in place: its windows' key. */
    private fun windows(): String? = plugins.textEncoder()?.windowsId

    /**
     * The centre the full-text vectors of the [type] are made and read with now; null of no encoder, or of none learnt.
     */
    fun of(type: TypeStore): FullTextCentre? = windows()?.let(type.fullTextCentres::of)

    /** Of every type whose encoder of windows has no centre kept, one learnt: what the data say. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val windows = windows() ?: return
        stores.types.filter { it.fullTextCentres.of(windows) == null }.forEach { plan(it.id, "encoder") }
    }

    /** The texts or the chapters of works of a type written, of a type with no centre: one learnt of them. */
    @EventListener
    fun textsWritten(event: WorkDocumentWriteEvent) {
        val type = stores.type(event.type)
        val windows = windows()
        val texts = WorkStage.VECTORS in event.stages || WorkStage.WINDOWS in event.stages
        if (texts && type != null && windows != null)
            if (type.fullTextCentres.of(windows) == null) plan(type.id, "parts")
    }

    private fun plan(typeId: String, reason: String) =
        tasks.plan(Task(KIND, type = typeId), null, reason) { fit(typeId) }

    /** The centre of the [typeId] learnt of its vectors drawn at random and kept; nothing of a type of too few. */
    internal fun fit(typeId: String) {
        val type = stores.type(typeId)
        val encoder = plugins.textEncoder()
        if (type == null || encoder == null) return
        val texts =
            type.sources.flatMap { s ->
                val meaningful = s.schema.texts.filter { it.searchByMeaning }.map { it.key }
                s.parts.windows.sampleTexts(CHAPTERS) +
                    if (meaningful.isEmpty()) emptyList() else s.items.sampleTexts(TEXTS, meaningful)
            }
        val vectors = texts.chunked(BATCH).flatMap { encoder.encodeWindows(it).flatten() }
        if (vectors.size < LEAST) {
            log.info("{}: the centre of the windows not learnt, of {} vectors", typeId, vectors.size)
            return
        }
        val sum = DoubleArray(vectors.first().size)
        vectors.forEach { v -> for (i in sum.indices) sum[i] += v[i].toDouble() }
        val now = Instant.now()
        val centre =
            FullTextCentre(
                now.toEpochMilli().toString(Character.MAX_RADIX),
                FloatArray(sum.size) { (sum[it] / vectors.size).toFloat() },
                FullTextCentre.rotation(sum.size, Random(now.toEpochMilli())),
                now,
                vectors.size,
            )
        type.fullTextCentres.keep(encoder.windowsId, centre)
        log.info("{}: the centre of the windows learnt of {} vectors", typeId, vectors.size)
        events.publishEvent(FullTextCentreFitEvent(typeId))
    }

    companion object {
        private val KIND = TaskKind.FIT_FULL_TEXT_CENTRE

        /** The chapters, and the texts searched by meaning, drawn of every source: their windows learnt of. */
        private const val CHAPTERS = 300
        private const val TEXTS = 2_000

        /** The texts a run of the encoder reads: memory, not meaning. */
        private const val BATCH = 32

        /** The least vectors a centre is learnt of. */
        private const val LEAST = 50
    }
}
