package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VALUE
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.impl.DSL

/**
 * THE ITEMS OF A SOURCE CHOSEN BY THEIR VALUES, of the index of the list ([ListIndex]): those whose filter facet has,
 * of every group of values asked, one at least — the values as the user sees them, the site's, the model's kept, the
 * user's word on top. No database read, no model run.
 */
object ListSelection {

    /** The ids of the items of the [source] whose facet [facetId] has a value of every one of the [groups]. */
    fun idsOf(index: ListIndex, source: String, facetId: String, groups: List<Set<String>>): Set<String> =
        index.searching { db ->
            val v = ITEM_VALUE
            val each = groups.map { g ->
                ITEM.ITEM_KEY.`in`(DSL.select(v.ITEM_KEY).from(v).where(v.FACET.eq(facetId), v.VALUE_KEY.`in`(g)))
            }
            db.select(ITEM.ITEM_KEY).from(ITEM).where(DSL.and(listOf(ITEM.SOURCE.eq(source)) + each)).fetchSet {
                ItemKey.parse(it.value1()!!).id
            }
        }
}
