package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.TaskKeys
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(ListIndexing::class.java)

/**
 * The documents of works of the [type] written anew, every stage of the tasks over the works to be done of them again,
 * the vectors of their site's text fields first — of each of the works from where ([from]); of an index made [anew]
 * whole.
 */
data class WorkDocumentWriteEvent(val type: String, val from: Map<WorkScope, TaskCursor>, val anew: Boolean = false)

/** What is to be written of a content type's index: the names of what changed since the last writing. */
class ListWaiting {
    /** The items whose documents are made anew, by source. */
    val rows = HashMap<String, MutableSet<String>>()

    /** The sources every document of which is made anew. */
    val whole = HashSet<String>()

    /** Whether works were merged or split. */
    var regrouped = false

    /** What waits as the queue keeps it ([TaskKeys.want]): a key of every name. */
    fun keys(): List<String> =
        rows.flatMap { (source, ids) -> ids.map { "$ROW$SEP$source$SEP$it" } } +
            whole.map { "$WHOLE$SEP$it" } +
            listOfNotNull(REGROUPED.takeIf { regrouped })

    companion object {
        private const val ROW = "row"
        private const val WHOLE = "whole"
        private const val REGROUPED = "regrouped"
        private const val SEP = '\t'

        /** What waits of the [keys] the queue keeps ([keys]). */
        fun of(keys: Collection<String>): ListWaiting =
            ListWaiting().apply {
                keys.forEach { k ->
                    val parts = k.split(SEP)
                    when (parts[0]) {
                        ROW -> rows.getOrPut(parts[1]) { HashSet() } += parts[2]
                        WHOLE -> whole += parts[1]
                        REGROUPED -> regrouped = true
                    }
                }
            }
    }
}

/**
 * THE INDEX OF THE LIST KEPT IN STEP WITH THE DATABASES ([ListIndex]), in the queue of the computations — a work of
 * every content type, after a quiet, of every change since the last one at once:
 * - the documents of the items a change names made anew, of every item of their works: their data, the user's answers
 *   and corrections, the values the models gave them as the index kept them;
 * - of a regrouping of the works, of every item of a work of several before and after;
 * - of a source whole, every item, those gone removed; an index of another format made anew whole. Of every document
 *   made anew every stage of the tasks over the works is to be done again, the vectors of the site's text fields first:
 *   told ([WorkDocumentWriteEvent]).
 *
 * What waits is the names of the items alone, never their data: the databases are read when the work runs.
 */
@Component
class ListIndexing(
    private val stores: Stores,
    private val works: Works,
    private val tasks: Tasks,
    private val indexes: ListIndexes,
    private val events: ApplicationEventPublisher,
) {
    /**
     * What the [change] names added to what waits of the content type [typeId] — kept in the queue ([TaskKeys.want]): a
     * stop leaves it to the next start — and its writing planned, for the [reason] the interface words
     * ([ListIndexEvents]).
     */
    fun change(typeId: String, reason: String, change: ListWaiting.() -> Unit) {
        tasks.keys.want(work(typeId), ListWaiting().apply(change).keys())
        plan(typeId, reason)
    }

    /** The writing of what waits of the content type [typeId] planned, for the [reason]; nothing added to it. */
    fun plan(typeId: String, reason: String) {
        // The user's own action the list is looked at for, and an index never made — an empty list — go ahead of the
        // background's works
        val asked = reason in USERS || (reason == FORMAT && !indexes.of(typeId).current())
        tasks.plan(
            work(typeId),
            Instant.now().plusSeconds(QUIET_SECONDS),
            reason,
            priority = TaskPriority.ASKED.takeIf { asked },
        ) {
            write(typeId)
        }
    }

    private fun work(typeId: String) = Task(WORK, type = typeId)

    // What the last run of the application left undone of it: planned again by the queue at its start — what it named
    // the queue kept
    init {
        tasks.resumption.resumes(WORK) { left, reason -> left.work.type?.let { plan(it, reason) } }
    }

    /**
     * What waits of the content type [typeId] written now, in the queue, for a caller that waits for it — what stands
     * for the background in a test ([io.github.vlsergey.recommend4me.task.inBackground]).
     */
    fun writeWaiting(typeId: String) = tasks.run(work(typeId)) { write(typeId) }

    /** Whether the index of the content type [typeId] is of this format. */
    fun current(typeId: String): Boolean = indexes.of(typeId).current()

    /** What waits of the content type [typeId] written to its index, and committed. */
    private fun write(typeId: String) {
        val type = stores.type(typeId) ?: return
        val taken = tasks.keys.wanted(work(typeId))
        if (taken.isEmpty()) return
        val w = ListWaiting.of(taken)
        val started = System.currentTimeMillis()
        val index = indexes.of(typeId)
        val clusters = works.of(type)
        // An index of another format made anew whole
        val anew = !index.current()
        if (anew) index.clear()
        val whole = if (anew) type.sources.map { it.id }.toSet() else w.whole
        val named = named(index, clusters, w, whole)
        var written = whole.sumOf { writeWhole(type, it, index, clusters, anew) }
        val from = HashMap<WorkScope, TaskCursor>()
        named.forEach { (sourceId, ids) ->
            type.source(sourceId)?.let { written += writeNamed(type, it, ids, index, clusters, from) }
        }
        index.commit()
        tasks.keys.done(work(typeId), taken)
        // Of a source written whole, every work from the first
        val told = if (whole.isEmpty()) from else WorkScope.entries.associateWith { TaskCursor.FIRST }
        if (told.isNotEmpty()) events.publishEvent(WorkDocumentWriteEvent(typeId, told, anew))
        log.info(
            "{}: the index of the list written in {} ms: {} sources whole, {} items made anew",
            typeId,
            System.currentTimeMillis() - started,
            whole.size,
            written,
        )
    }

    /**
     * The documents of the items [ids] of the source [s] made anew, those of the items gone removed: the values the
     * models gave them as the index kept them, every stage to be done again — of each scope from the first of them on,
     * kept in [from]. How many were written.
     */
    private fun writeNamed(
        type: TypeStore,
        s: SourceStore,
        ids: Set<String>,
        index: ListIndex,
        clusters: WorkClusters,
        from: MutableMap<WorkScope, TaskCursor>,
    ): Int {
        val rows = ListRows.read(type, s, ids, clusters, ListModelValues.of(index, s.id, ids))
        val found = rows.mapTo(HashSet()) { it.key.id }
        index.replace(rows, ids.filter { it !in found }.map { ItemKey(s.id, it) })
        rows.forEach { r ->
            val scope = if (r.values.marked) WorkScope.MARKED else WorkScope.OTHER
            val at = TaskCursor(r.values.prediction ?: Double.NEGATIVE_INFINITY, r.key.toString())
            from[scope] = TaskCursor.earlier(from[scope], at)!!
        }
        return rows.size
    }

    /**
     * Every document of the source [sourceId] made anew, those of the items gone removed: how many were written. The
     * values the models gave its items as the index kept them — none of an index made [anew] — every stage of the tasks
     * over the works to be done again ([WorkFields] first).
     */
    private fun writeWhole(
        type: TypeStore,
        sourceId: String,
        index: ListIndex,
        clusters: WorkClusters,
        anew: Boolean,
    ): Int {
        val s = type.source(sourceId) ?: return 0
        val kept = if (anew) emptyMap() else ListModelValues.ofEvery(index, sourceId)
        val rows = ListRows.read(type, s, null, clusters, kept)
        index.replace(rows)
        index.keepOnly(sourceId, rows.mapTo(HashSet()) { it.key.id })
        return rows.size
    }

    /**
     * The items whose documents are made anew, by source, of a source not made anew [whole]: those the changes named
     * with every item of their works, and of a regrouping every item of a work of several before and after it.
     */
    private fun named(
        index: ListIndex,
        clusters: WorkClusters,
        w: ListWaiting,
        whole: Set<String>,
    ): Map<String, Set<String>> {
        val keys = HashSet<ItemKey>()
        w.rows.forEach { (source, ids) -> ids.forEach { keys += clusters.members(ItemKey(source, it)) } }
        if (w.regrouped) {
            keys += index.linked()
            clusters.linkedWorks().forEach(keys::addAll)
        }
        return keys.filter { it.source !in whole }.groupBy({ it.source }, { it.id }).mapValues { it.value.toSet() }
    }

    companion object {
        /** The work of the queue writing the index of a content type's list. */
        val WORK = TaskKind.WRITE_LIST_INDEX

        /** The quiet after a change before the index is written: the changes of a moment written at once. */
        private const val QUIET_SECONDS = 1L

        /** The reason of an index of another format, or none: made anew whole. */
        const val FORMAT = "listFormat"

        /** The reasons that are the user's own actions: their writing goes ahead of the background's works. */
        private val USERS = setOf("answer")
    }
}
