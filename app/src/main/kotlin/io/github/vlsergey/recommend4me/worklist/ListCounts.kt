package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VALUE
import org.jooq.impl.DSL

/**
 * How many items of the list have each value of a filter facet ([counts]), of how many items in all of the sources that
 * have it ([total]), and how many of them have none ([without]).
 */
class ValueCounts(val counts: Map<String, Int>, val total: Int, val without: Int)

/**
 * THE VALUES OF A FILTER FACET COUNTED OF THE INDEX OF THE LIST ([ListIndex]), exactly: every item that has a value —
 * the site's, the model's kept, the user's word on top — counted, no work read, no model run.
 */
object ListCounts {

    /** The values of the filter facet [facetId] of the items of the [sources] that have it. */
    fun of(index: ListIndex, facetId: String, sources: Collection<String>): ValueCounts = index.searching { db ->
        val v = ITEM_VALUE
        val counts =
            db.select(v.VALUE_KEY, DSL.countDistinct(v.ITEM_KEY))
                .from(v)
                .where(v.FACET.eq(facetId))
                .groupBy(v.VALUE_KEY)
                .fetch()
                .associate { it.value1()!! to it.value2()!! }
        val of = ITEM.SOURCE.`in`(sources)
        ValueCounts(
            counts,
            db.selectCount().from(ITEM).where(of).fetchOne(0, Int::class.java) ?: 0,
            db.selectCount()
                .from(ITEM)
                .where(of, ITEM.ITEM_KEY.notIn(DSL.select(v.ITEM_KEY).from(v).where(v.FACET.eq(facetId))))
                .fetchOne(0, Int::class.java) ?: 0,
        )
    }
}
