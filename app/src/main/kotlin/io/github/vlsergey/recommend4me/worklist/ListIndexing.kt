package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.workload.Work
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
}

/**
 * THE INDEX OF THE LIST KEPT IN STEP WITH THE DATABASES ([ListIndex]), in the queue of the computations — a work of
 * every content type, after a quiet, of every change since the last one at once:
 * - the documents of the items a change names made anew, of every item of their works: their data, the user's answers
 *   and corrections, the values the model gives them
 *   ([ModelValuesChanged][io.github.vlsergey.recommend4me.suggestion.ModelValuesChanged]);
 * - of a regrouping of the works, of every item of a work of several before and after;
 * - of a grade, a mark of interest, the predictions of a training, the values of the items' works alone, in place;
 * - on the start, of every item of every source, those gone removed: what was changed while the application was
 *   stopped, or told to no one, found; an index of another format made anew whole.
 *
 * What waits is the names of the items alone, never their data: the databases are read when the work runs.
 */
@Component
class ListIndexing(
    private val stores: Stores,
    private val works: Works,
    private val workload: Workload,
    private val indexes: ListIndexes,
) {
    /** What waits of every content type; taken whole by its writing, under its own lock. */
    private val waiting = HashMap<String, ListWaiting>()

    /**
     * What the [change] names added to what waits of the content type [typeId], and its writing planned, for the
     * [reason] the interface words ([ListIndexEvents]).
     */
    fun change(typeId: String, reason: String, change: ListWaiting.() -> Unit) {
        synchronized(waiting) { waiting.getOrPut(typeId) { ListWaiting() }.change() }
        // The user's own action the list is looked at for, and an index never made — an empty list — go ahead of the
        // background's works
        val asked = reason in USERS || (reason == START && !indexes.of(typeId).current())
        workload.plan(Work(WORK, type = typeId), Instant.now().plusSeconds(QUIET_SECONDS), reason, asked = asked) {
            write(typeId)
        }
    }

    /**
     * What waits of the content type [typeId] written now, in the queue, for a caller that waits for it — what stands
     * for the background in a test ([io.github.vlsergey.recommend4me.workload.inBackground]).
     */
    fun writeWaiting(typeId: String) = workload.run(Work(WORK, type = typeId)) { write(typeId) }

    /** Whether the index of the content type [typeId] is of this format. */
    fun current(typeId: String): Boolean = indexes.of(typeId).current()

    /** What waits of the content type [typeId] written to its index, and committed. */
    private fun write(typeId: String) {
        val type = stores.type(typeId) ?: return
        val w = synchronized(waiting) { waiting.remove(typeId) } ?: return
        val started = System.currentTimeMillis()
        val index = indexes.of(typeId)
        val clusters = works.of(type)
        // An index of another format made anew whole
        val anew = !index.current()
        if (anew) index.clear()
        val whole = if (anew) type.sources.map { it.id }.toSet() else w.whole
        val named = named(index, clusters, w, whole)
        var written = 0
        whole.forEach { sourceId ->
            val s = type.source(sourceId) ?: return@forEach
            val rows = ListRows.read(type, s, null, clusters)
            index.replace(rows)
            index.keepOnly(sourceId, rows.mapTo(HashSet()) { it.key.id })
            written += rows.size
        }
        named.forEach { (sourceId, ids) ->
            val s = type.source(sourceId) ?: return@forEach
            val rows = ListRows.read(type, s, ids, clusters)
            val found = rows.mapTo(HashSet()) { it.key.id }
            index.replace(rows, ids.filter { it !in found }.map { ItemKey(sourceId, it) })
            written += rows.size
        }
        val valued =
            w.values.filter { it.source !in whole && named[it.source]?.contains(it.id) != true }.groupBy { it.source }
        valued.forEach { (sourceId, keys) ->
            val s = type.source(sourceId) ?: return@forEach
            index.updateValues(ListRows.valuesOf(type, s, keys.map { it.id }).mapKeys { ItemKey(sourceId, it.key) })
        }
        index.commit()
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
        const val WORK = "listIndex"

        /** The quiet after a change before the index is written: the changes of a moment written at once. */
        private const val QUIET_SECONDS = 1L

        /** The reasons of the start of the application. */
        const val START = "start"

        /** The reasons that are the user's own actions: their writing goes ahead of the background's works. */
        private val USERS = setOf("grade", "interest", "answer")
    }
}
