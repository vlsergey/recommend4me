package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkClusters
import org.jooq.Condition
import org.jooq.DSLContext

/** An item of the list found: its key, the work it is of, its [rank] among the work's items, the shown one 0. */
class FoundItem(val key: ItemKey, val work: ItemKey, val rank: Long)

/**
 * THE WORKS OF SEVERAL ITEMS OF THE INDEX OF THE LIST ([ListIndex]): few — made by hand — so read item by item, while
 * every other item is a work of its own and a row by itself, sorted as it is, no group looked up for it.
 */
internal object ListWorks {

    /** Every item of a work of several that the [where] picks, by its key. */
    fun linkedOf(db: DSLContext, where: Condition): Map<ItemKey, FoundItem> =
        found(db, where.and(ITEM.LINKED.isTrue)).associateBy { it.key }

    /** Every item the [where] picks, with its work and its rank: of few items alone — every one is read. */
    fun found(db: DSLContext, where: Condition): List<FoundItem> =
        db.select(ITEM.ITEM_KEY, ITEM.WORK, ITEM.WORK_RANK).from(ITEM).where(where).fetch { r ->
            FoundItem(ItemKey.parse(r.value1()!!), ItemKey.parse(r.value2()!!), r.value3()!!.toLong())
        }

    /**
     * The works of the items [keys] as the cards read them: of a work of several every item of it, the one it is shown
     * with first — of the index, not of every work of the type.
     */
    fun clustersOf(db: DSLContext, keys: Collection<ItemKey>): WorkClusters {
        val works =
            if (keys.isEmpty()) emptySet()
            else linkedOf(db, ITEM.ITEM_KEY.`in`(keys.map { it.toString() })).values.mapTo(HashSet()) { it.work }
        val members = if (works.isEmpty()) emptyList() else found(db, ITEM.WORK.`in`(works.map { it.toString() }))
        return WorkClusters.of(
            members.groupBy { it.work }.values.filter { it.size > 1 }.map { g -> g.sortedBy { it.rank }.map { it.key } }
        )
    }
}
