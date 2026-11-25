package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.piece.FittedPieces
import io.github.vlsergey.recommend4me.piece.PieceProjectionFitEvent
import io.github.vlsergey.recommend4me.piece.PieceProjections
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.worklist.ListIndex
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.NextWorks
import io.github.vlsergey.recommend4me.worklist.StageSweeps
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/** The vectors of the windows of the chapters of these items of a source were written. */
data class SiteItemChapterVectorChangeEvent(val source: String, val ids: List<String>)

/**
 * Every change of the chapters of the works of the [scope] of the [type] encoded ([WorkStage.WINDOWS]): the stages
 * reading their windows, or waiting for them, look again.
 */
data class WorkChapterWindowFillEvent(val type: String, val scope: WorkScope)

/**
 * THE VECTORS OF THE CHAPTERS OF THE WORKS — A TASK OVER THE WORKS (owner, 2026-11-19: "why is this not split into the
 * rated and the rest? let us split"): the text of every part of every work read, encoded window by window: a vector for
 * every few hundred words, so a chapter is a set of vectors and one scene unlike the rest is not averaged away. And
 * paragraph by paragraph ([Paragraphs]): what the tags are held against to be offered by the work's own text, a
 * paragraph quoted beside its tag. A part read again with another text is encoded again. The works are taken as every
 * stage takes them, by the dates of their chapters past its mark ([WorkStage.WINDOWS]), every work of an encoder not
 * told before; their parts read by their keys; a task of the marked and rated works of a type, and one of the others
 * after every other task, a [PORTION_WORKS] of them a turn ([turn]) — the highest expected first, from where the last
 * turn left.
 */
@Service
class PartVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val tasks: Tasks,
    private val lists: ListIndexes,
    private val sweeps: StageSweeps,
    private val projections: PieceProjections,
) {

    @Volatile private var busy = false

    fun running(): Boolean = busy

    /** The documents of works written anew, of their chapters changed: their parts encoded. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) {
        if (STAGE in event.stages)
            WorkScope.entries.forEach {
                plan(event.type, it, "parts", TaskCursor.FIRST)
            }
    }

    // What the last run of the application left undone of it: planned again by the queue at its start, from where it
    // was to go on — one of no type, of every type
    init {
        tasks.resumption.resumes(TaskKind.ENCODE_SITE_ITEM_CHAPTERS) { left, reason ->
            val scopes = left.work.works?.let(::listOf) ?: WorkScope.entries
            val types = left.work.type?.let(::listOf) ?: stores.types.map { it.id }
            types.forEach { type -> scopes.forEach { plan(type, it, reason, left.cursor ?: TaskCursor.FIRST) } }
        }
    }

    /**
     * Of every type whose chapters were encoded by another encoder than the one of now — or never told by which —,
     * every work's chapters encoded again: what the data say, nothing for the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val encoder = plugins.textEncoder() ?: return
        stores.types
            .filter { !madeBy(lists.of(it.id), Encoding(encoder, projections.of(it))) }
            .forEach { type -> WorkScope.entries.forEach { plan(type.id, it, "encoder", TaskCursor.FIRST) } }
    }

    /** The projection of the pieces of a type learnt anew: every chapter of it to be read again for its pieces. */
    @EventListener
    fun projectionFitted(event: PieceProjectionFitEvent) =
        WorkScope.entries.forEach { plan(event.type, it, "encoder", TaskCursor.FIRST) }

    /**
     * The chapters of the [type] by the [encoder]: of an encoder other than the one they were made by last — never
     * told, or told before another —, told so, and the stage to be done of every work again: the chapters of every work
     * are read by its key as its turn comes, those encoded already passed over.
     */
    private fun madeBy(type: TypeStore, encoder: Encoding) {
        val index = lists.of(type.id)
        if (madeBy(index, encoder)) return
        val now = System.currentTimeMillis()
        index.stages.changed(WINDOWS_SINCE, now)
        index.stages.changed(windowsBy(encoder), now)
        sweeps.reset(STAGE, type.id, type.sources.map { it.id })
    }

    /** The chapters of the works of the [scope] of the [typeId] planned, for the [reason], from the [cursor] on. */
    fun plan(typeId: String, scope: WorkScope, reason: String, cursor: TaskCursor) =
        tasks.plan(
            Task(TaskKind.ENCODE_SITE_ITEM_CHAPTERS, type = typeId, works = scope),
            null,
            reason,
            priority = NextWorks.priority(scope),
            cursor = cursor,
        ) {
            turn(typeId, scope, tasks.running()?.cursor ?: TaskCursor.FIRST)?.let {
                tasks.again(if (tasks.outranked) "yielded" else "sweep", cursor = it)
            }
        }

    /**
     * ONE TURN: of the works of the [scope] of the [typeId] whose parts wait to be encoded, the next [portion] from the
     * [cursor] on encoded; where the next turn goes on from — none when none is left. Stopped between its batches for a
     * work the user asked for ([Tasks.outranked]), it goes on from the [cursor] again: the works it encoded wait no
     * more, and are passed over.
     */
    internal fun turn(typeId: String, scope: WorkScope, cursor: TaskCursor, portion: Int = PORTION_WORKS): TaskCursor? {
        val type = stores.type(typeId)
        val textEncoder = plugins.textEncoder()
        if (type == null || textEncoder == null) return null
        val encoder = Encoding(textEncoder, projections.of(type))
        madeBy(type, encoder)
        // The works of the chapters changed past the stage's mark, the parts of each read by its key: no work's parts
        // are read to find what waits (owner, 2026-11-25: every turn of 50 works read the 4.6 million parts of ficbook)
        val items = sweeps.next(STAGE, typeId, type.sources.map { it.id }, scope, cursor, portion)
        var gaveWay = false
        if (items.isNotEmpty()) {
            val encoded = HashSet<ItemKey>()
            gaveWay = encodeAll(items.groupBy({ it.key.source }, { it.key.id }), encoder, encoded)
            // The works whose windows were encoded dated so: what the sets and the search read of them changed
            if (!gaveWay) sweeps.done(STAGE, typeId, scope, items.map { it.key })
            sweeps.made(STAGE, typeId, scope, encoded)
            lists.of(typeId).commit()
        }
        // None waiting any more: every change of the chapters done up to the sweep's horizon
        if (!gaveWay && items.size < portion && sweeps.complete(STAGE, typeId, scope))
            events.publishEvent(WorkChapterWindowFillEvent(typeId, scope))
        return if (gaveWay) cursor else items.lastOrNull()?.cursor()?.takeIf { items.size == portion }
    }

    /**
     * Every part of the [works] waiting encoded, a batch after another, the works of windows kept in [encoded]; whether
     * it gave way to a work the user asked for ([Tasks.outranked]) before the end — stopped between its batches as the
     * application stops too.
     */
    private fun encodeAll(
        works: Map<String, List<String>>,
        encoder: Encoding,
        encoded: MutableSet<ItemKey>,
    ): Boolean {
        var gaveWay = false
        try {
            val batches = batchesOf(works, encoder).iterator()
            while (!tasks.stopping && !gaveWay && batches.hasNext()) {
                encode(batches.next(), encoder, encoded)
                gaveWay = tasks.outranked
            }
        } finally {
            busy = false
        }
        return gaveWay
    }

    /** A batch of parts of one source to encode: their windows, or their paragraphs. */
    private class Batch(val source: SourceStore, val parts: List<PartWaiting>, val windows: Boolean)

    /**
     * The batches of the parts of the [works] of every source waiting to be encoded, [BATCH] parts each: their windows,
     * then their paragraphs — the parts of the portion read once, by their works.
     */
    private fun batchesOf(works: Map<String, List<String>>, encoder: Encoding): List<Batch> =
        works.flatMap { (source, ids) ->
            val s = stores.source(source) ?: return@flatMap emptyList()
            val e = encoder.encoder
            val waiting =
                s.parts.windows.waitingOf(ids, e.windowsId, Paragraphs.keyOf(e.id), encoder.pieces?.projection?.key)
            waiting.filter { it.windows || it.pieces }.chunked(BATCH).map { Batch(s, it, windows = true) } +
                waiting.filter { it.paragraphs }.chunked(BATCH).map { Batch(s, it, windows = false) }
        }

    /**
     * The parts of the [batch] encoded — their windows with their pieces when the type has a projection of them, or
     * their paragraphs —, the works of windows kept in [encoded].
     */
    private fun encode(batch: Batch, with: Encoding, encoded: MutableSet<ItemKey>) {
        val s = batch.source
        val todo = s.parts.windows.texts(batch.parts)
        if (todo.isEmpty()) return
        busy = true
        val encoder = with.encoder
        if (batch.windows) {
            // The pieces made by the run of the windows: the windows' vectors of it, the pieces' codes kept beside
            val fitted = with.pieces
            val pieces = fitted?.let { f -> encoder.pieces?.encode(todo.map { it.content }, f.projection) }
            val windows = pieces?.map { it.windows } ?: encoder.encodeWindows(todo.map { it.content })
            // A part whose windows are made, of its pieces alone, has its windows as they were: nothing read changed
            val ofWindows = batch.parts.filter { it.windows }.map { it.itemId to it.partId }.toSet()
            val changed = todo.filter { (it.itemId to it.partId) in ofWindows }
            todo.forEachIndexed { i, part ->
                if ((part.itemId to part.partId) in ofWindows)
                    s.parts.windows.saveVectors(part.itemId, part.partId, encoder.windowsId, part.hash, windows[i])
                if (fitted != null && pieces != null)
                    s.parts.pieces.save(
                        part.itemId,
                        part.partId,
                        fitted.projection.key,
                        fitted.codebook.codes(pieces[i].pieces),
                    )
            }
            // What the models learnt of these works read: told, the models fitted of them fitted again
            if (changed.isNotEmpty())
                events.publishEvent(SiteItemChapterVectorChangeEvent(s.id, changed.map { it.itemId }.distinct()))
            changed.forEach { encoded += ItemKey(s.id, it.itemId) }
            return
        }
        // The paragraphs of every part of the batch encoded together, then given back to their parts: a chapter's few
        // dozen alone ran the graph's batches half empty
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
    }

    companion object {
        /** Parts per encoder call: a chapter is tens of windows already. */
        private const val BATCH = 8

        /** Paragraphs per encoder call: a chapter is hundreds of them. */
        private const val PARAGRAPHS = 256

        /**
         * The works whose parts a turn encodes: a work is tens of chapters, and a turn of a thousand would hold the
         * queue for hours — minutes, not days (user, 2026-11-17).
         */
        private const val PORTION_WORKS = 50

        /** The stage of the tasks over the works it is. */
        private val STAGE = WorkStage.WINDOWS
    }
}

/** Whether the chapters of the [index] are encoded by the [encoder] of now since it was last told to make them. */
private fun madeBy(index: ListIndex, encoder: Encoding): Boolean =
    index.stages.since(windowsBy(encoder)).let { it != null && it == index.stages.since(WINDOWS_SINCE) }

/** What the index of the list tells of since when the chapters are to be encoded by the encoder of now. */
private const val WINDOWS_SINCE = "windows"

/**
 * What the index of the list tells of since when the chapters were encoded by the [encoder], when last told: its
 * windows, its paragraphs and the projection of its pieces — a new one of any of them makes every chapter again.
 */
private fun windowsBy(encoder: Encoding) =
    "windowsBy:${encoder.encoder.windowsId}|${Paragraphs.keyOf(encoder.encoder.id)}" +
        (encoder.pieces?.let { "|pieces:${it.projection.key}" } ?: "")

/** The encoder of the texts, and what the pieces of the type are made with — none of no projection of them. */
private class Encoding(val encoder: TextEncoder, val pieces: FittedPieces?)
