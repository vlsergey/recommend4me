package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_NUMBER
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VALUE
import io.github.vlsergey.recommend4me.item.ItemKey
import java.util.Arrays
import org.jooq.DSLContext

/**
 * EVERY ITEM OF A SOURCE AS THE INDEX OF THE LIST KEEPS IT ([ListFacetValues.everyItem]), by their places, the ids
 * ascending ([ids]): its values by their numbers ([values]) — a value one number whichever item has it, its name in
 * [names], "<facet id>:<value>" —, its title as the user corrected it when asked for ([titles]), its numbers by their
 * ids ([numbers]: nought of one not known). Arrays by place, not maps by id: of the 1.4 million works of ficbook the
 * maps of strings and boxed numbers took most of the reading (owner, 2026-11-20).
 */
class ItemRows(
    val ids: List<String>,
    val values: Array<IntArray>,
    val titles: List<String?>,
    val numbers: Map<String, DoubleArray>,
    val names: List<String>,
)

/**
 * The reading of every item of a [source]: its items and their titles in one pass, ordered by their ids; then the
 * values of the facets [facetIds] in one pass of theirs, each numbered once a value, and the numbers [numberIds] alike,
 * every row put at its item's place.
 */
internal class EveryItem(
    private val source: String,
    private val facetIds: List<String>,
    private val numberIds: List<String>,
    private val titled: Boolean,
) {
    fun read(db: DSLContext): ItemRows {
        val keys = ArrayList<String>()
        val titleOf = HashMap<String, String?>()
        db.select(ITEM.ITEM_KEY, ITEM.TITLE)
            .from(ITEM)
            .where(ITEM.SOURCE.eq(source))
            .fetchSize(STREAMED)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach { r ->
                    val id = ItemKey.parse(r.value1()!!).id
                    keys += id
                    if (titled) titleOf[id] = r.value2()
                }
            }
        val ids = keys.toTypedArray().also { Arrays.parallelSort(it) }.asList()
        val at = HashMap<String, Int>(ids.size * 2).also { m -> ids.forEachIndexed { i, id -> m[id] = i } }
        val prefix = ItemKey(source, "").toString()
        return ItemRows(
            ids,
            valuesOf(db, at),
            if (titled) ids.map { titleOf[it] } else List(ids.size) { null },
            numbersOf(db, at, prefix),
            names,
        )
    }

    private val names = ArrayList<String>()
    private val byName = HashMap<String, Int>()

    /** Of every item by its place [at], the numbers of its values of the facets read. */
    private fun valuesOf(db: DSLContext, at: Map<String, Int>): Array<IntArray> {
        val buffers = arrayOfNulls<IntArray>(at.size)
        val counts = IntArray(at.size)
        if (facetIds.isNotEmpty()) {
            val v = ITEM_VALUE
            db.select(v.ITEM_KEY, v.FACET, v.VALUE_KEY)
                .from(v)
                .join(ITEM)
                .on(ITEM.ITEM_KEY.eq(v.ITEM_KEY))
                .where(ITEM.SOURCE.eq(source), v.FACET.`in`(facetIds))
                .fetchSize(STREAMED)
                .fetchLazy()
                .use { cursor ->
                    cursor.forEach { r ->
                        val i = at[ItemKey.parse(r.value1()!!).id] ?: return@forEach
                        val name = "${r.value2()}:${r.value3()}"
                        val code = byName.getOrPut(name) { names.size.also { names += name } }
                        val own = buffers[i] ?: IntArray(START).also { buffers[i] = it }
                        val grown =
                            if (counts[i] == own.size) own.copyOf(own.size * 2).also { buffers[i] = it } else own
                        grown[counts[i]++] = code
                    }
                }
        }
        return Array(at.size) { i -> buffers[i]?.copyOf(counts[i]) ?: EMPTY }
    }

    /** Of every number of [numberIds], its value of every item by its place [at]; nought of one not known. */
    private fun numbersOf(db: DSLContext, at: Map<String, Int>, prefix: String): Map<String, DoubleArray> {
        val out = numberIds.associateWith { DoubleArray(at.size) }
        if (numberIds.isEmpty()) return out
        val n = ITEM_NUMBER
        db.select(n.ITEM_KEY, n.NUMBER_ID, n.CONTENT)
            .from(n)
            .where(n.NUMBER_ID.`in`(numberIds), n.ITEM_KEY.startsWith(prefix))
            .fetchSize(STREAMED)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach { r ->
                    val i = at[r.value1()!!.substring(prefix.length)] ?: return@forEach
                    out.getValue(r.value2()!!)[i] = r.value3()!!
                }
            }
        return out
    }

    private companion object {
        /** The rows streamed at a time: memory, not meaning. */
        const val STREAMED = 50_000

        /** The values of an item first made room for. */
        const val START = 8

        val EMPTY = IntArray(0)
    }
}
