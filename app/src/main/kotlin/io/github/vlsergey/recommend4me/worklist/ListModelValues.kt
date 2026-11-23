package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_MODEL
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext

/**
 * THE VALUES THE MODELS GAVE THE ITEMS, AS THE INDEX OF THE LIST KEEPS THEM — the one place they are kept (user,
 * 2026-11-18): of an item, by the facet's key, its values beyond the site's own, in their order. Read of the connection
 * the caller gives: a task writing the index as written so far ([ListIndex.written]: what the filling of the values
 * compares the new with, what the rows of the search are made of); a screen as last committed ([ListIndex.searching]),
 * never waiting for the writing (2026-11-23: the page of the duplicates waited 2 minutes behind a task choosing its
 * works).
 */
object ListModelValues {
    /** Of the items [ids] of the [source]: item to facet to values; an item of none, or of no row, left out. */
    fun of(db: DSLContext, source: String, ids: Collection<String>): Map<String, Map<String, List<String>>> {
        val out = HashMap<String, HashMap<String, MutableList<String>>>()
        val m = ITEM_MODEL
        ids.distinct().chunked(ListIndex.BATCH).forEach { chunk ->
            db.select(m.ITEM_KEY, m.FACET, m.VALUE_KEY)
                .from(m)
                .where(m.ITEM_KEY.`in`(chunk.map { ItemKey(source, it).toString() }))
                .orderBy(m.ITEM_KEY, m.FACET, m.POS)
                .fetch { r ->
                    out.getOrPut(ItemKey.parse(r.value1()!!).id) { HashMap() }.getOrPut(r.value2()!!) { ArrayList() } +=
                        r.value3()!!
                }
        }
        return out
    }
}
