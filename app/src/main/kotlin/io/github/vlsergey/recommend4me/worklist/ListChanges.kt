package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ListChanges::class.java)

/**
 * WHAT CHANGED OF A CONTENT TYPE AFTER WHERE ITS INDEX OF THE LIST STANDS, a portion at a time (owner, 2026-11-22: "it
 * picks of the database every document changed after the mark, sorted, and puts them into the index in portions, moving
 * its watermark"): of every source the next items after its cursor in the order of their changes
 * ([io.github.vlsergey.recommend4me.item.ItemChanges.after]), of the normalized works the next works the user's word
 * changed ([io.github.vlsergey.recommend4me.work.WorkChanges.after]) with their items, and every item of the works of
 * them all — what [ListIndexing] writes anew; and where each stands after them. A source every item of which changed at
 * once after its documents were written — a dictionary its values are drafted by — is taken from its first item again.
 */
internal object ListChanges {

    /**
     * One portion: the items to be written, by source ([named]); of every source where its cursor stands after them
     * ([items]) and up to when its documents then hold every change of its items ([written]); of the works likewise
     * ([works], [worksWritten]); whether anything is left after them ([more]).
     */
    class Portion(
        val named: Map<String, Set<String>>,
        val items: Map<String, ItemCursor?>,
        val written: Map<String, Long>,
        val works: WorkCursor?,
        val worksWritten: Long,
        val more: Boolean,
    )

    /**
     * The next portion of the [type] after the [cursors], of at most [limit] items of a source and [limit] works: of
     * those taken in a writing begun at [started], every item of their works as the [clusters] tell them.
     */
    fun next(type: TypeStore, clusters: WorkClusters, cursors: ListCursors, limit: Int, started: Long): Portion {
        val keys = HashSet<ItemKey>()
        val items = HashMap<String, ItemCursor?>()
        val written = HashMap<String, Long>()
        var more = false
        val told = HashMap<String, Int>()
        type.sources.forEach { s ->
            val (from, base) = startOf(s, cursors, started)
            val picked = s.changes.after(from, limit)
            picked.forEach { keys += clusters.members(ItemKey(s.id, it.id)) }
            if (picked.isNotEmpty()) told[s.id] = picked.size
            val caught = picked.size < limit
            more = more || !caught
            items[s.id] = picked.lastOrNull() ?: from
            written[s.id] = if (caught) started else maxOf(base, dateOf(picked.last()) - 1)
        }
        val before = cursors.works()
        val works = type.works.after(before, limit)
        type.works.itemsOf(works.map { it.id }).values.flatten().forEach { keys += clusters.members(it) }
        val worksCaught = works.size < limit
        val named = keys.groupBy({ it.source }, { it.id }).mapValues { it.value.toSet() }
        log.info(
            "{}: changed after where its index stands: items {}, works {}; named with their works' items {}",
            type.id,
            told,
            works.size,
            named.mapValues { it.value.size },
        )
        return Portion(
            named,
            items,
            written,
            works.lastOrNull() ?: before,
            if (worksCaught) started else maxOf(cursors.worksWritten(), works.last().at.toEpochMilli() - 1),
            more || !worksCaught,
        )
    }

    /**
     * Whether anything of the [type] is left after the [cursors]: an item of a source after its cursor, a source to be
     * taken from its first item again, a work after the works' cursor.
     */
    fun any(type: TypeStore, cursors: ListCursors): Boolean =
        type.works.after(cursors.works(), 1).isNotEmpty() ||
            type.sources.any { s -> again(s, cursors) || s.changes.after(cursors.of(s.id), 1).isNotEmpty() }

    /**
     * Where the source [s] is taken from in a writing begun at [started], and up to when its documents hold every
     * change before it: of a source every item of which changed at once after that — a dictionary its values are
     * drafted by — its first item, and the date before that change; else its cursor, and its date as kept.
     */
    private fun startOf(s: SourceStore, cursors: ListCursors, started: Long): Pair<ItemCursor?, Long> {
        if (!again(s, cursors)) return cursors.of(s.id) to cursors.written(s.id)
        val every = s.changes.every()!!.toEpochMilli()
        cursors.begun(s.id, started)
        return null to minOf(cursors.written(s.id), every - 1)
    }

    /**
     * Whether every item of the source [s] changed at once after its documents were written and after its items were
     * last taken from the first: they are to be taken from the first again.
     */
    private fun again(s: SourceStore, cursors: ListCursors): Boolean {
        val every = s.changes.every()?.toEpochMilli() ?: return false
        return every > maxOf(cursors.written(s.id), cursors.begun(s.id))
    }

    /** The date of the change of the item a [cursor] stands at, milliseconds: nought of an item of none. */
    private fun dateOf(cursor: ItemCursor): Long = cursor.at?.toEpochMilli() ?: 0L
}
