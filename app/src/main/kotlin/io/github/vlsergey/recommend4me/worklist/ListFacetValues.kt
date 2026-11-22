package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VALUE
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext

/**
 * THE VALUES OF THE ITEMS AS THE LIST KEEPS THEM ([ListDocuments]): of every filter facet, the site's, the model's kept
 * and the user's word on top — what the filters pass a work by, and the cards of a page show (user, 2026-11-17: no
 * model run for a card). Read of the rows of the items alone, by their keys, or of every item of a source in one pass.
 */
object ListFacetValues {

    /** Of the items [keys], every filter facet's values by its filter id, in their order; an item of no row none. */
    fun of(index: ListIndex, keys: Collection<ItemKey>): Map<ItemKey, Map<String, List<String>>> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<ItemKey, LinkedHashMap<String, MutableList<String>>>()
        val v = ITEM_VALUE
        index.searching { db ->
            keys.distinct().chunked(ListIndex.BATCH).forEach { chunk ->
                db.select(v.ITEM_KEY, v.FACET, v.VALUE_KEY)
                    .from(v)
                    .where(v.ITEM_KEY.`in`(chunk.map { it.toString() }))
                    .orderBy(v.ITEM_KEY, v.FACET, v.POS)
                    .fetch { r ->
                        out.getOrPut(ItemKey.parse(r.value1()!!)) { LinkedHashMap() }
                            .getOrPut(r.value2()!!) { ArrayList() } += r.value3()!!
                    }
            }
        }
        return out
    }

    /** Every value of the filter facet [facetId] some item has. */
    fun valuesOf(db: DSLContext, facetId: String): List<String> =
        db.selectDistinct(ITEM_VALUE.VALUE_KEY).from(ITEM_VALUE).where(ITEM_VALUE.FACET.eq(facetId)).fetch {
            it.value1()!!
        }

    /**
     * Of every item of the [source] the index holds, its values of the filter facets [facetIds] by their numbers — a
     * value one number whichever item has it, its name in [ItemRows.names] —, the keys of its values of the facets read
     * [apart] ([ItemRows.apart]), and its numbers of [numberIds]: what a computation over every work reads of the
     * index, in one pass of each table, no database read and no value a string an item (user, 2026-11-18).
     */
    fun everyItem(
        index: ListIndex,
        source: String,
        facetIds: Collection<String>,
        numberIds: Collection<String>,
        titles: Boolean = false,
        apart: Collection<String> = emptyList(),
    ): ItemRows = index.searching { db ->
        EveryItem(source, facetIds.toList(), numberIds.toList(), titles, apart.toList()).read(db)
    }
}
