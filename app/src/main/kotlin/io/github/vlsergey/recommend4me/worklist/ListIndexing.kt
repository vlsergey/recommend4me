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
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import java.time.Instant
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
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

/**
 * THE INDEX OF THE LIST KEPT IN STEP WITH THE DATABASES ([ListIndex]), in the queue of the computations — a work of
 * every content type, after a quiet, of every change since its last writing at once, by the dates the databases keep
 * (owner, 2026-11-21: "why name them and keep the names at all? the index has the date it was last built, a work the
 * date it was last changed"; the date of the writing kept with the index's commit, [WRITTEN]):
 * - the documents of the items changed since made anew ([io.github.vlsergey.recommend4me.item.ItemChanges]), and of the
 *   items of the works the user's word changed ([io.github.vlsergey.recommend4me.work.WorkChanges]: an answer, a grade,
 *   a mark of interest, two works merged or taken apart), of every item of their works: their data, the user's answers
 *   and corrections, the values the models gave them as the index kept them;
 * - of a source changed whole, every item; an index of another format made anew whole; the documents of the items gone
 *   from their source removed. Of every document made anew every stage of the tasks over the works is to be done again,
 *   the vectors of the site's text fields first: told ([WorkDocumentWriteEvent]).
 *
 * Nothing is named in the queue: what changed is read of the databases when the work runs, and a stop loses nothing.
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
     * The items [ids] of the source [s] changed in what the index holds of them — of none, every item of it: dated
     * ([io.github.vlsergey.recommend4me.item.ItemChanges]), the writing planned for the [reason] the interface words.
     */
    fun itemsChanged(s: SourceStore, ids: Collection<String>, reason: String) {
        if (ids.isEmpty()) s.changes.everyChanged(Instant.now()) else s.changes.changed(ids, Instant.now())
        plan(s.type, reason)
    }

    /**
     * The works of the [items] of the content type [type] changed by the user's word: dated
     * ([io.github.vlsergey.recommend4me.work.WorkChanges]), the writing planned for the [reason].
     */
    fun worksChanged(type: TypeStore, items: Collection<ItemKey>, reason: String) {
        type.works.changed(items, Instant.now())
        plan(type.id, reason)
    }

    /** The writing of what changed of the content type [typeId] planned, for the [reason]. */
    fun plan(typeId: String, reason: String) {
        // The user's own action the list is looked at for, and an index never made — an empty list — go ahead of the
        // background's works: of the user's change, first only of a press (owner, 2026-11-19: first only "the button
        // 'find the duplicates anew' and the choice of the model; the button 'train'; a page of a graded work opened;
        // the buttons of the page of the models; a field's model searched anew")
        val users = reason in USERS || (reason == FORMAT && !indexes.of(typeId).current())
        tasks.plan(
            work(typeId),
            Instant.now().plusSeconds(QUIET_SECONDS),
            reason,
            priority = TaskPriority.USER.takeIf { users },
        ) {
            write(typeId)
        }
    }

    private fun work(typeId: String) = Task(WORK, type = typeId)

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(WORK) { left, reason -> left.work.type?.let { plan(it, reason) } }
    }

    /**
     * What changed of the content type [typeId] written now, in the queue, for a caller that waits for it — what stands
     * for the background in a test ([io.github.vlsergey.recommend4me.task.inBackground]).
     */
    fun writeWaiting(typeId: String) = tasks.run(work(typeId)) { write(typeId) }

    /** Whether the index of the content type [typeId] is of this format. */
    fun current(typeId: String): Boolean = indexes.of(typeId).current()

    /** Whether anything of the [type] changed since its index was last written: what a start plans its writing of. */
    fun pending(type: TypeStore): Boolean = ListChanges.any(type, writtenOf(indexes.of(type.id)))

    /** What changed of the content type [typeId] since the last writing written to its index, and committed. */
    private fun write(typeId: String) {
        val type = stores.type(typeId) ?: return
        val started = Instant.now()
        val index = indexes.of(typeId)
        // An index of another format made anew whole
        val anew = !index.current()
        if (anew) index.clear()
        val since = writtenOf(index)
        val whole = if (anew) type.sources.map { it.id }.toSet() else ListChanges.whole(type, since)
        val clusters = works.of(type)
        val named = ListChanges.named(type, clusters, since, whole)
        val gathered = System.currentTimeMillis()
        val from = HashMap<WorkScope, TaskCursor>()
        var written = whole.sumOf { writeWhole(type, it, index, clusters, anew, from) }
        named.forEach { (sourceId, ids) ->
            type.source(sourceId)?.let { s ->
                ids.chunked(PORTION).forEach { written += writeNamed(s, it.toSet(), index, clusters, from) }
            }
        }
        val gone = type.sources.filter { it.id !in whole }.sumOf { removeGone(it, index) }
        val replaced = System.currentTimeMillis()
        index.stages.changed(WRITTEN, started.toEpochMilli())
        index.commit()
        val committed = System.currentTimeMillis()
        // Of an index made anew, every work from the first
        val told = if (!anew) from else WorkScope.entries.associateWith { TaskCursor.FIRST }
        if (told.isNotEmpty()) events.publishEvent(WorkDocumentWriteEvent(typeId, told, anew))
        log.info(
            "{}: the index of the list written in {} ms: {} sources whole, {} items made anew, {} gone — the changes " +
                "read {}, read and replaced {}, committed {}",
            typeId,
            committed - started.toEpochMilli(),
            whole.size,
            written,
            gone,
            gathered - started.toEpochMilli(),
            replaced - gathered,
            committed - replaced,
        )
    }

    /**
     * Of the source [s], the documents of its items gone removed — when the index holds more of them than the source
     * has items: an item taken out of the library, nothing of it left to be dated —; how many.
     */
    private fun removeGone(s: SourceStore, index: ListIndex): Int {
        val documents = index.written { IndexSearcher(it).count(TermQuery(Term(ListDocuments.SOURCE, s.id))) }
        if (documents <= s.items.count()) return 0
        return index.keepOnly(s.id, s.items.keys().mapTo(HashSet()) { it.id })
    }

    /**
     * The documents of the items [ids] of the source [s] made anew, those of the items gone removed: the values the
     * models gave them and their predictions as the index kept them, every stage to be done again — of each scope from
     * the first of them on, kept in [from]. How many were written.
     */
    private fun writeNamed(
        s: SourceStore,
        ids: Set<String>,
        index: ListIndex,
        clusters: WorkClusters,
        from: MutableMap<WorkScope, TaskCursor>,
    ): Int {
        val predicted = index.written { ListPredictions.of(it, s.id, ids) }
        val rows = ListRows.read(s, ids, clusters, ListModelValues.of(index, s.id, ids), predicted)
        val found = rows.mapTo(HashSet()) { it.key.id }
        index.replace(rows, ids.filter { it !in found }.map { ItemKey(s.id, it) })
        rows.forEach { r -> from(r, from) }
        return rows.size
    }

    /**
     * Every item of the source [sourceId] read whole, the documents of those its document is not alike made anew
     * ([sameAs]; owner, 2026-11-21: "read really everything, and rewrite what changed") — every one of an index made
     * [anew] —, those of the items gone removed: how many were written. The values the models gave its items and their
     * predictions as the index kept them; of every document made anew every stage of the tasks over the works to be
     * done again ([WorkFields] first) — of each scope from the first of them on, kept in [from] — a document alike
     * keeping its own.
     */
    private fun writeWhole(
        type: TypeStore,
        sourceId: String,
        index: ListIndex,
        clusters: WorkClusters,
        anew: Boolean,
        from: MutableMap<WorkScope, TaskCursor>,
    ): Int {
        val s = type.source(sourceId) ?: return 0
        val kept = if (anew) emptyMap() else ListModelValues.ofEvery(index, sourceId)
        val predicted = if (anew) emptyMap() else index.written { ListPredictions.ofEvery(it, sourceId) }
        val rows = ListRows.read(s, null, clusters, kept, predicted)
        // Compared a portion at a time with the documents as written before any is: one reading of the index
        val other =
            if (anew) rows
            else
                index.written { reader ->
                    rows.chunked(PORTION).flatMap { portion ->
                        val written = ListKeptRows.of(reader, sourceId, portion.map { it.key.id })
                        portion.filter { r -> written[r.key.id]?.let(r::sameAs) != true }
                    }
                }
        index.replace(other)
        index.keepOnly(sourceId, rows.mapTo(HashSet()) { it.key.id })
        other.forEach { r -> from(r, from) }
        return other.size
    }

    companion object {
        /** The work of the queue writing the index of a content type's list. */
        val WORK = TaskKind.WRITE_LIST_INDEX

        /**
         * Of the stages the index keeps with its commits ([StageTimes]): since when its documents hold every change of
         * the databases — the start of their last writing, milliseconds.
         */
        const val WRITTEN = "written"

        /** The documents a writing reads and replaces at a time: the memory of a writing of many, not a meaning. */
        private const val PORTION = 10_000

        /** The place of the row [r] among the works of its scope, the earliest of each scope kept in [from]. */
        private fun from(r: ListRow, from: MutableMap<WorkScope, TaskCursor>) {
            val scope = if (r.values.marked) WorkScope.MARKED else WorkScope.OTHER
            val at = TaskCursor(r.values.prediction ?: Double.NEGATIVE_INFINITY, r.key.toString())
            from[scope] = TaskCursor.earlier(from[scope], at)!!
        }

        /** Since when the documents of the [index] hold every change; of an index never so written, of every date. */
        private fun writtenOf(index: ListIndex): Instant =
            index.stages.since(WRITTEN)?.let(Instant::ofEpochMilli) ?: Instant.EPOCH

        /** The quiet after a change before the index is written: the changes of a moment written at once. */
        private const val QUIET_SECONDS = 1L

        /** The reason of an index of another format, or none: made anew whole. */
        const val FORMAT = "listFormat"

        /** The reasons that are the user's own actions: their writing goes ahead of the background's works. */
        private val USERS = setOf("answer", "grade", "interest")
    }
}
