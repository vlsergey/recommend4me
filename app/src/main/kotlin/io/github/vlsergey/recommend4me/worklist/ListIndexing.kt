package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
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
 * The documents of works of the [type] written anew: the [stages] of what changed, or behind, look again
 * ([StageSweeps.written]).
 */
data class WorkDocumentWriteEvent(val type: String, val stages: Set<WorkStage>)

/**
 * THE INDEX OF THE LIST KEPT IN STEP WITH THE DATABASES ([ListIndex]), in the queue of the computations — a work of
 * every content type, after a quiet, of every change since its last writing at once, by the dates the databases keep
 * (owner, 2026-11-21: "why name them and keep the names at all? the index has the date it was last built, a work the
 * date it was last changed"; the date of the writing kept with the index's commit, [WRITTEN]):
 * - the items changed since ([io.github.vlsergey.recommend4me.item.ItemChanges]) and the items of the works the user's
 *   word changed ([io.github.vlsergey.recommend4me.work.WorkChanges]: an answer, a grade, a mark of interest, two works
 *   merged or taken apart), with every item of their works, read and compared with their documents as written: those
 *   not alike made anew ([sameAs]) — their data, the user's answers and corrections, when the work last changed — with
 *   their stages as written: a stage done before its work changed is done again of itself ([StageBehind]);
 * - of a source changed whole — a dictionary its values are drafted by —, every item read and compared, those not alike
 *   made anew with every stage to be done again — of every source so when the index holds no date of writing (owner,
 *   2026-11-22: "you were simply to mark that the index is current as of nought"); a document other than written by
 *   what it holds anew alone — what the documents had not held, its dates alike — written with its stages as they were,
 *   nothing to be done again; the documents of the items gone from their source removed. The passes over the works told
 *   from the first written on ([WorkDocumentWriteEvent]).
 *
 * Nothing is named in the queue: what changed is read of the databases when the work runs, and a stop loses nothing.
 */
@Component
class ListIndexing(
    private val stores: Stores,
    private val works: Works,
    private val tasks: Tasks,
    private val indexes: ListIndexes,
    private val sweeps: StageSweeps,
    private val events: ApplicationEventPublisher,
    private val plugins: Plugins,
) {
    /**
     * The [datum] of the items [ids] of the source [s] changed — of none, every item of it: dated
     * ([io.github.vlsergey.recommend4me.item.ItemChanges]), the writing planned for the [reason] the interface words.
     */
    fun itemsChanged(s: SourceStore, ids: Collection<String>, datum: WorkDatum, reason: String) {
        if (ids.isEmpty()) s.changes.everyChanged(Instant.now()) else s.changes.changed(ids, datum, Instant.now())
        plan(s.type, reason)
    }

    /**
     * The [datum] of the works of the [items] of the content type [type] changed by the user's word: dated
     * ([io.github.vlsergey.recommend4me.work.WorkChanges]), the writing planned for the [reason].
     */
    fun worksChanged(type: TypeStore, items: Collection<ItemKey>, datum: WorkDatum, reason: String) {
        type.works.changed(items, datum, Instant.now())
        plan(type.id, reason)
    }

    /** The writing of what changed of the content type [typeId] planned, for the [reason]. */
    fun plan(typeId: String, reason: String) {
        // The user's own action the list is looked at for, and an index never made — an empty list — go ahead of the
        // background's works: of the user's change, first only of a press (owner, 2026-11-19: first only "the button
        // 'find the duplicates anew' and the choice of the model; the button 'train'; a page of a graded work opened;
        // the buttons of the page of the models; a field's model searched anew")
        val users = reason in USERS || writtenOf(indexes.of(typeId)) == Instant.EPOCH
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

    /**
     * Whether anything of the [type] changed since its index was last written — of an index of no date of writing,
     * everything: what a start plans its writing of.
     */
    fun pending(type: TypeStore): Boolean =
        writtenOf(indexes.of(type.id)).let { it == Instant.EPOCH || ListChanges.any(type, it) }

    /** What changed of the content type [typeId] since the last writing written to its index, and committed. */
    private fun write(typeId: String) {
        val type = stores.type(typeId) ?: return
        val started = Instant.now()
        val index = indexes.of(typeId)
        index.keepSources(type.sources.mapTo(HashSet()) { it.id })
        val since = writtenOf(index)
        // Of an index of no date of writing, every source read whole and compared with its documents
        val whole = if (since == Instant.EPOCH) type.sources.map { it.id }.toSet() else ListChanges.whole(type, since)
        val clusters = works.of(type)
        val named = ListChanges.named(type, clusters, since, whole)
        val gathered = System.currentTimeMillis()
        val changed = HashSet<WorkDatum>()
        var written = whole.sumOf { writeWhole(type, it, index, clusters, changed) }
        named.forEach { (sourceId, ids) ->
            type.source(sourceId)?.let { s ->
                ids.chunked(PORTION).forEach { written += writeNamed(s, it.toSet(), index, clusters, changed) }
            }
        }
        val gone = type.sources.filter { it.id !in whole }.sumOf { removeGone(it, index) }
        val replaced = System.currentTimeMillis()
        index.stages.changed(WRITTEN, started.toEpochMilli())
        val sourceIds = type.sources.map { it.id }
        val due = sweeps.written(typeId, sourceIds, changed, since.toEpochMilli(), started.toEpochMilli())
        index.commit()
        val committed = System.currentTimeMillis()
        if (due.isNotEmpty()) events.publishEvent(WorkDocumentWriteEvent(typeId, due))
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
     * The documents of the items [ids] of the source [s] made anew where they are not alike as written ([sameAs]), with
     * the stages of the tasks over their works and the dates of what they made as written — a stage takes the work
     * again by the date of what it reads ([StageSweeps]) —, those of the items gone removed: the values the models gave
     * them and their predictions as the index kept them; the kinds of data whose dates changed kept in [changed]. How
     * many were written.
     */
    private fun writeNamed(
        s: SourceStore,
        ids: Set<String>,
        index: ListIndex,
        clusters: WorkClusters,
        changed: MutableSet<WorkDatum>,
    ): Int {
        val (predicted, written) = index.written { ListPredictions.of(it, s.id, ids) to ListKeptRows.of(it, s.id, ids) }
        val rows =
            ListRows.read(s, ids, clusters, ListModelValues.of(index, s.id, ids), predicted, plugins.textEncoder()?.id)
        val found = rows.mapTo(HashSet()) { it.key.id }
        val other = rows.mapNotNull { r ->
            written[r.key.id].let { if (it != null && r.sameAs(it)) null else r.over(it) }
        }
        other.forEach { r -> changed += r.changedOf(written[r.key.id]) }
        index.replace(other, ids.filter { it !in found }.map { ItemKey(s.id, it) })
        return other.size
    }

    /**
     * Every item of the source [sourceId] read whole — the vectors of its texts in one pass of them —, the documents of
     * those its document is not alike made anew ([sameAs]; owner, 2026-11-21: "read really everything, and rewrite what
     * changed"), those of the items gone removed: how many were written. The values the models gave its items and their
     * predictions as the index kept them, the stages of the tasks over their works and the dates of what they made as
     * written. A document other than written with every outer date alike: of what is drafted of it changed — a
     * dictionary its values are drafted by —, its item dated so ([WorkDatum.DATA]); of what the documents held not
     * before alone ([draftedOtherThan] not), written as it is, nothing to be done again of it. The kinds of data whose
     * dates changed kept in [changed].
     */
    private fun writeWhole(
        type: TypeStore,
        sourceId: String,
        index: ListIndex,
        clusters: WorkClusters,
        changed: MutableSet<WorkDatum>,
    ): Int {
        val s = type.source(sourceId) ?: return 0
        val kept = ListModelValues.ofEvery(index, sourceId)
        val predicted = index.written { ListPredictions.ofEvery(it, sourceId) }
        val read = ListRows.read(s, null, clusters, kept, predicted, plugins.textEncoder()?.id)
        val filters = ListRows.filterIds(s)
        return RowVectors.attached(s, read, plugins.textEncoder()?.id) { rows ->
            // Compared a portion at a time with the documents as written before any is: one reading of the index
            val undated = ArrayList<ListRow>()
            val other = index.written { reader ->
                rows.chunked(PORTION).flatMap { portion ->
                    val written = ListKeptRows.of(reader, sourceId, portion.map { it.key.id })
                    portion.mapNotNull { r ->
                        val before = written[r.key.id]
                        when {
                            before == null -> r.also { changed += it.changedOf(null) }
                            r.sameAs(before) -> null
                            r.datedOtherThan(before) -> r.over(before).also { changed += it.changedOf(before) }
                            r.draftedOtherThan(before, filters) -> r.over(before).also { undated += it }
                            else -> r.over(before)
                        }
                    }
                }
            }
            // Other than written with every date the databases keep alike: of what is drafted of it, its item dated so
            val now = Instant.now()
            if (undated.isNotEmpty()) {
                s.changes.changed(undated.map { it.key.id }, WorkDatum.DATA, now)
                changed += WorkDatum.DATA
            }
            val drafted = undated.mapTo(HashSet()) { it.key }
            index.replace(other.map { if (it.key in drafted) it.dated(WorkDatum.DATA, now.toEpochMilli()) else it })
            index.keepOnly(sourceId, rows.mapTo(HashSet()) { it.key.id })
            other.size
        }
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

        /** Since when the documents of the [index] hold every change; of an index never so written, of every date. */
        internal fun writtenOf(index: ListIndex): Instant =
            index.stages.since(WRITTEN)?.let(Instant::ofEpochMilli) ?: Instant.EPOCH

        /** The quiet after a change before the index is written: the changes of a moment written at once. */
        private const val QUIET_SECONDS = 1L

        /** The reasons that are the user's own actions: their writing goes ahead of the background's works. */
        private val USERS = setOf("answer", "grade", "interest")
    }
}
