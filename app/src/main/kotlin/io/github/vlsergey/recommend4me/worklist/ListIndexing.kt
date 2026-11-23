package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
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
 * every content type, after a quiet — a consumer of the changes as the stages are (owner, 2026-11-22: "it picks of the
 * database every document changed after the mark, sorted, and puts them into the index in portions, moving its
 * watermark"): of every source the items changed after where its writing stands, in the order of their changes, and the
 * works the user's word changed likewise — an answer, a grade, a mark of interest, two works merged or taken apart —,
 * with every item of their works ([ListChanges]), a portion a turn, read and compared with their documents as written:
 * those not alike made anew ([sameAs]) with their stages and the dates of what they made as written — a stage takes the
 * work again by the date of what it reads ([StageSweeps]); a document other than written with its dates alike by what
 * is drafted of it — a dictionary its values are drafted by — dated so ([WorkDatum.DATA]). Where the writing stands
 * moves with every portion, kept with the commit ([ListCursors]); of an index current as of nought, every item is taken
 * from the first. A dictionary changed names no item: what is drafted of it anew tells the works it changed. The
 * documents of the items gone from their source removed as every change is written; the passes over the works told what
 * changed ([WorkDocumentWriteEvent]).
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
     * The [datum] of the items [ids] of the source [s] changed: dated
     * ([io.github.vlsergey.recommend4me.item.ItemChanges]), the writing planned for the [reason] the interface words.
     */
    fun itemsChanged(s: SourceStore, ids: Collection<String>, datum: WorkDatum, reason: String) {
        if (ids.isEmpty()) return
        s.changes.changed(ids, datum, Instant.now())
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
        val users = reason in USERS || never(typeId)
        tasks.plan(
            work(typeId),
            Instant.now().plusSeconds(QUIET_SECONDS),
            reason,
            priority = TaskPriority.USER.takeIf { users },
        ) {
            if (write(typeId)) tasks.again(MORE)
        }
    }

    private fun work(typeId: String) = Task(WORK, type = typeId)

    /** Whether the index of the content type [typeId] was never written: an empty list. */
    private fun never(typeId: String): Boolean {
        val cursors = ListCursors(indexes.of(typeId))
        return stores.type(typeId)?.sources?.all { cursors.written(it.id) == 0L } ?: false
    }

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(WORK) { left, reason -> left.work.type?.let { plan(it, reason) } }
    }

    /**
     * What changed of the content type [typeId] written now, in the queue, for a caller that waits for it — what stands
     * for the background in a test ([io.github.vlsergey.recommend4me.task.inBackground]).
     */
    fun writeWaiting(typeId: String) =
        tasks.run(work(typeId)) {
            var more = true
            while (more) more = write(typeId)
        }

    /** Whether anything of the [type] changed after where its index stands: what a start plans its writing of. */
    fun pending(type: TypeStore): Boolean = ListChanges.any(type, ListCursors(indexes.of(type.id)))

    /**
     * The next portion of what changed of the content type [typeId] after where its index stands written to it, where
     * it stands moved after them, committed: whether more is left.
     */
    private fun write(typeId: String): Boolean {
        val type = stores.type(typeId) ?: return false
        val started = Instant.now()
        val index = indexes.of(typeId)
        index.keepSources(type.sources.mapTo(HashSet()) { it.id })
        val cursors = ListCursors(index)
        val before = type.sources.associate { it.id to cursors.held(it.id) }
        val clusters = works.of(type)
        val portion = ListChanges.next(type, clusters, cursors, PORTION, started.toEpochMilli())
        val gathered = System.currentTimeMillis()
        val changed = HashSet<WorkDatum>()
        var written = 0
        portion.named.forEach { (sourceId, ids) ->
            type.source(sourceId)?.let { s ->
                ids.chunked(PORTION).forEach { written += writeNamed(s, it.toSet(), index, clusters, changed) }
            }
        }
        // The documents of the items gone, as every change is written
        val gone = if (portion.more) 0 else type.sources.sumOf { removeGone(it, index) }
        val replaced = System.currentTimeMillis()
        portion.items.forEach { (source, at) -> cursors.set(source, at) }
        portion.written.forEach { (source, at) -> cursors.written(source, at) }
        portion.works?.let(cursors::setWorks)
        cursors.worksWritten(portion.worksWritten)
        val after = type.sources.associate { it.id to cursors.held(it.id) }
        val due = sweeps.written(typeId, before, after, changed)
        index.commit()
        val committed = System.currentTimeMillis()
        if (due.isNotEmpty()) events.publishEvent(WorkDocumentWriteEvent(typeId, due))
        log.info(
            "{}: the index of the list written in {} ms: {} items made anew, {} gone{} — the changes read {}, read " +
                "and replaced {}, committed {}",
            typeId,
            committed - started.toEpochMilli(),
            written,
            gone,
            if (portion.more) ", more left" else "",
            gathered - started.toEpochMilli(),
            replaced - gathered,
            committed - replaced,
        )
        return portion.more
    }

    /**
     * Of the source [s], the rows of its items gone removed — when the index holds more of them than the source has
     * items: an item taken out of the library, nothing of it left to be dated —; how many.
     */
    private fun removeGone(s: SourceStore, index: ListIndex): Int {
        val rows = index.written { db ->
            db.selectCount().from(ITEM).where(ITEM.SOURCE.eq(s.id)).fetchOne(0, Int::class.java) ?: 0
        }
        if (rows <= s.items.count()) return 0
        return index.keepOnly(s.id, s.items.keys().mapTo(HashSet()) { it.id })
    }

    /**
     * The documents of the items [ids] of the source [s] made anew where they are not alike as written ([sameAs]), with
     * the stages of the tasks over their works and the dates of what they made as written — a stage takes the work
     * again by the date of what it reads ([StageSweeps]) —, those of the items gone removed: the values the models gave
     * them and their predictions as the index kept them; the kinds of data whose dates changed kept in [changed]. A
     * document other than written with every outer date alike: of what is drafted of it changed — a dictionary its
     * values are drafted by —, its item dated so ([WorkDatum.DATA]); of what the documents held not before alone,
     * written as it is, nothing to be done again of it. How many were written.
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
            ListRows.read(
                s,
                ids,
                clusters,
                index.written { ListModelValues.of(it, s.id, ids) },
                predicted,
                plugins.textEncoder()?.id,
            )
        val found = rows.mapTo(HashSet()) { it.key.id }
        val filters = ListRows.filterIds(s)
        val drafted = HashSet<String>()
        val other = rows.mapNotNull { r ->
            val before = written[r.key.id]
            when {
                before == null -> r.also { changed += it.changedOf(null) }
                r.sameAs(before) -> null
                r.datedOtherThan(before) -> r.over(before).also { changed += it.changedOf(before) }
                r.draftedOtherThan(before, filters) -> r.over(before).also { drafted += it.key.id }
                else -> r.over(before)
            }
        }
        // Other than written with every date the databases keep alike: of what is drafted of it, its item dated so
        val now = Instant.now()
        if (drafted.isNotEmpty()) {
            s.changes.changed(drafted, WorkDatum.DATA, now)
            changed += WorkDatum.DATA
        }
        index.replace(
            other.map { if (it.key.id in drafted) it.dated(WorkDatum.DATA, now.toEpochMilli()) else it },
            ids.filter { it !in found }.map { ItemKey(s.id, it) },
        )
        return other.size
    }

    companion object {
        /** The work of the queue writing the index of a content type's list. */
        val WORK = TaskKind.WRITE_LIST_INDEX

        /** The documents a writing reads and replaces at a time: the memory of a writing of many, not a meaning. */
        private const val PORTION = 10_000

        /** The reason a writing with more left after its portion plans the next. */
        private const val MORE = "sweep"

        /** The quiet after a change before the index is written: the changes of a moment written at once. */
        private const val QUIET_SECONDS = 1L

        /** The reasons that are the user's own actions: their writing goes ahead of the background's works. */
        private val USERS = setOf("answer", "grade", "interest")
    }
}
