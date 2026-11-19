package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.WorkKind
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.WorkModelValues
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.WorkKeys
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(ListIndexing::class.java)

/** What is to be written of a content type's index: the names of what changed since the last writing. */
class ListWaiting {
    /** The items whose documents are made anew, by source. */
    val rows = HashMap<String, MutableSet<String>>()

    /** The sources every document of which is made anew. */
    val whole = HashSet<String>()

    /** The items whose work's values alone changed. */
    val values = HashSet<ItemKey>()

    /** Whether works were merged or split. */
    var regrouped = false

    /** What waits as the queue keeps it ([WorkKeys.want]): a key of every name. */
    fun keys(): List<String> =
        rows.flatMap { (source, ids) -> ids.map { "$ROW$SEP$source$SEP$it" } } +
            whole.map { "$WHOLE$SEP$it" } +
            values.map { "$VALUES$SEP${it.source}$SEP${it.id}" } +
            listOfNotNull(REGROUPED.takeIf { regrouped })

    companion object {
        private const val ROW = "row"
        private const val WHOLE = "whole"
        private const val VALUES = "values"
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
                        VALUES -> values += ItemKey(parts[1], parts[2])
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
 *   and corrections, the values the models give them, made now of them ([WorkModelValues]);
 * - of a regrouping of the works, of every item of a work of several before and after;
 * - of a grade, a mark of interest, the predictions of a training, the values of the items' works alone, in place;
 * - of a source whole, every item, those gone removed, the values the models gave them as the index kept them; an index
 *   of another format made anew whole, its values filled after ([ListFilling]).
 *
 * What waits is the names of the items alone, never their data: the databases are read when the work runs.
 */
@Component
class ListIndexing(
    private val stores: Stores,
    private val works: Works,
    private val workload: Workload,
    private val indexes: ListIndexes,
    private val modelValues: WorkModelValues,
) {
    /**
     * What the [change] names added to what waits of the content type [typeId] — kept in the queue ([WorkKeys.want]): a
     * stop leaves it to the next start — and its writing planned, for the [reason] the interface words
     * ([ListIndexEvents]).
     */
    fun change(typeId: String, reason: String, change: ListWaiting.() -> Unit) {
        workload.keys.want(work(typeId), ListWaiting().apply(change).keys())
        plan(typeId, reason)
    }

    /** The writing of what waits of the content type [typeId] planned, for the [reason]; nothing added to it. */
    fun plan(typeId: String, reason: String) {
        // The user's own action the list is looked at for, and an index never made — an empty list — go ahead of the
        // background's works
        val asked = reason in USERS || (reason == FORMAT && !indexes.of(typeId).current())
        workload.plan(work(typeId), Instant.now().plusSeconds(QUIET_SECONDS), reason, asked = asked) { write(typeId) }
    }

    private fun work(typeId: String) = Work(WORK, type = typeId)

    // What the last run of the application left undone of it: planned again by the queue at its start — what it named
    // the queue kept
    init {
        workload.resumption.resumes(WORK) { left, reason -> left.work.type?.let { plan(it, reason) } }
    }

    /**
     * What waits of the content type [typeId] written now, in the queue, for a caller that waits for it — what stands
     * for the background in a test ([io.github.vlsergey.recommend4me.workload.inBackground]).
     */
    fun writeWaiting(typeId: String) = workload.run(work(typeId)) { write(typeId) }

    /** Whether the index of the content type [typeId] is of this format. */
    fun current(typeId: String): Boolean = indexes.of(typeId).current()

    /** What waits of the content type [typeId] written to its index, and committed. */
    private fun write(typeId: String) {
        val type = stores.type(typeId) ?: return
        val taken = workload.keys.wanted(work(typeId))
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
        named.forEach { (sourceId, ids) ->
            val s = type.source(sourceId) ?: return@forEach
            val rows = ListRows.read(type, s, ids, clusters, modelValues.of(s, ids))
            val found = rows.mapTo(HashSet()) { it.key.id }
            index.replace(rows, ids.filter { it !in found }.map { ItemKey(sourceId, it) })
            written += rows.size
        }
        val valued =
            w.values.filter { it.source !in whole && named[it.source]?.contains(it.id) != true }.groupBy { it.source }
        valued.forEach { (sourceId, keys) ->
            val s = type.source(sourceId) ?: return@forEach
            val values = ListRows.valuesOf(type, s, keys.map { it.id })
            index.update(values.entries.associate { (id, v) -> ItemKey(sourceId, id) to ListDocuments.valueFields(v) })
        }
        index.commit()
        workload.keys.done(work(typeId), taken)
        log.info(
            "{}: the index of the list written in {} ms: {} sources whole, {} items made anew, {} items' values",
            typeId,
            System.currentTimeMillis() - started,
            whole.size,
            written,
            valued.values.sumOf { it.size },
        )
    }

    /**
     * Every document of the source [sourceId] made anew, those of the items gone removed: how many were written. The
     * values the models gave its items as the index kept them — none of an index made [anew]: the filling makes them
     * after the models.
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
        val WORK = WorkKind.LIST_INDEX

        /** The quiet after a change before the index is written: the changes of a moment written at once. */
        private const val QUIET_SECONDS = 1L

        /** The reason of an index of another format, or none: made anew whole. */
        const val FORMAT = "listFormat"

        /** The reasons that are the user's own actions: their writing goes ahead of the background's works. */
        private val USERS = setOf("grade", "interest", "answer")
    }
}
