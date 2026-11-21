package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.NextWorks
import io.github.vlsergey.recommend4me.worklist.StageSweeps
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
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
 * paragraph quoted beside its tag. A part read again with another text is encoded again. What is to be encoded the
 * store of the parts tells; a task of the marked and rated works of a type, and one of the others after every other
 * task, a [PORTION_WORKS] of them a turn ([turn]) — the highest expected first, from where the last turn left.
 */
@Service
class PartVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val tasks: Tasks,
    private val lists: ListIndexes,
    private val sweeps: StageSweeps,
) {

    @Volatile private var busy = false

    fun running(): Boolean = busy

    /** The documents of works written anew, of their chapters changed: their parts encoded. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) {
        if (STAGE in event.stages)
            WorkScope.entries.forEach {
                plan(event.type, it, if (event.anew) "listFormat" else "parts", TaskCursor.FIRST)
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
        val encoder = plugins.textEncoder()
        val waiting =
            if (type == null || encoder == null) emptyList()
            else
                type.sources.flatMap { s ->
                    s.parts.windows.itemsToEncode(encoder.windowsId, Paragraphs.keyOf(encoder.id)).map {
                        ItemKey(s.id, it)
                    }
                }
        val sources = type?.sources.orEmpty().map { it.id }
        // Of none waiting, a sweep of none: its mark moves up to its horizon all the same
        val items =
            if (type == null || encoder == null) emptyList()
            else sweeps.next(STAGE, typeId, sources, scope, cursor, portion, NextWorks.among(waiting))
        var gaveWay = false
        if (items.isNotEmpty()) {
            val works = items.groupBy({ it.key.source }, { it.key.id }).mapValues { it.value.toSet() }
            val encoded = HashSet<ItemKey>()
            try {
                val reached = Reached()
                while (!tasks.stopping && !gaveWay && encodeSome(reached, works, encoded) > 0) gaveWay = tasks.outranked
            } finally {
                busy = false
            }
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
     * Of one run, the last part of every source given to encode, of its windows and of its paragraphs: the next batch
     * looked for past it. A part left unencoded is met again on the next run.
     */
    private class Reached {
        val windows = HashMap<String, Pair<String, String>>()
        val paragraphs = HashMap<String, Pair<String, String>>()
    }

    /**
     * Encodes a batch of parts of every source's works of the [portion], their windows and then their paragraphs, from
     * past those [reached], the works whose windows were encoded kept in [encoded]; returns how many.
     */
    private fun encodeSome(reached: Reached, portion: Map<String, Set<String>>, encoded: MutableSet<ItemKey>): Int {
        val encoder = plugins.textEncoder() ?: return 0
        var done = 0
        stores.sources.forEach { s ->
            val works = portion[s.id].orEmpty()
            if (works.isEmpty()) return@forEach
            val todo = s.parts.windows.toEncode(encoder.windowsId, BATCH, reached.windows[s.id], works)
            if (todo.isEmpty()) return@forEach
            reached.windows[s.id] = todo.last().place
            busy = true
            val windows = encoder.encodeWindows(todo.map { it.content })
            todo.forEachIndexed { i, part ->
                s.parts.windows.saveVectors(part.itemId, part.partId, encoder.windowsId, part.hash, windows[i])
            }
            // What the models learnt of these works read: told, the models fitted of them fitted again
            events.publishEvent(SiteItemChapterVectorChangeEvent(s.id, todo.map { it.itemId }.distinct()))
            todo.forEach { encoded += ItemKey(s.id, it.itemId) }
            done += todo.size
        }
        stores.sources.forEach { s ->
            val works = portion[s.id].orEmpty()
            if (works.isEmpty()) return@forEach
            val todo =
                s.parts.paragraphs.paragraphsToEncode(
                    Paragraphs.keyOf(encoder.id),
                    BATCH,
                    reached.paragraphs[s.id],
                    works,
                )
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
