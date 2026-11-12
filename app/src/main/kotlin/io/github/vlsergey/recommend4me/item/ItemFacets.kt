package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_DESCRIPTION
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_FACET
import io.github.vlsergey.recommend4me.source.FacetValue
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** THE VALUES THE SITE GIVES ITS ITEMS of every facet, in their order, as the source database holds them. */
interface ItemFacets {
    /** The values of the facet of the item written in place of those it had, each named in the dictionary. */
    fun setFacet(itemId: String, facet: String, values: List<FacetValue>)

    /**
     * Removes the values, the names and the descriptions of every facet not among [facets]; how many values were
     * removed of each.
     */
    fun keepOnlyFacets(facets: Collection<String>): Map<String, Int>

    /** How many items have each value of a facet, as the site gives them. */
    fun valueUses(facet: String): Map<String, Int>

    /** The facets of one item: facet to keys in order. */
    fun facets(itemId: String): Map<String, List<String>>

    /**
     * The facets of the items [ids]: item to facet to keys in order. By their ids up to a batch of them ([ITEM_BATCH]);
     * of more, every value of every item streamed once and picked from, each list put in order after: by thousands of
     * ids the values of 31 000 works of 75 000 took 5 s.
     */
    fun facetsOf(ids: Collection<String>): Map<String, Map<String, List<String>>>

    /** The items the site gives each of the [keys] of the facet: key to items. */
    fun itemsWith(facet: String, keys: Collection<String>): Map<String, List<String>>

    /** Every item's keys of one facet, streamed item by item. */
    fun forEachFacet(facet: String, action: (itemId: String, keys: List<String>) -> Unit)

    /** Every facet of every item, streamed: (item, facet, key). */
    fun forEachFacetValue(action: (itemId: String, facet: String, key: String) -> Unit)
}

/**
 * The [ItemFacets] of the source database [db]: [changed] told of every item whose values were written, the values'
 * names written in the [dictionary].
 */
internal class ItemFacetRepository(
    private val db: DSLContext,
    private val changed: (Collection<String>) -> Unit,
    private val dictionary: FacetDictionary,
) : ItemFacets {

    override fun setFacet(itemId: String, facet: String, values: List<FacetValue>) {
        val distinct = values.distinctBy { it.key }
        // The names first: whoever hears of the change reads the values by them (a site's fandom bound by its name)
        val named = distinct.filter { it.name != null }.associate { it.key to it.name!! }
        if (named.isNotEmpty()) dictionary.nameFacetValues(facet, named)
        val before =
            db.select(ITEM_FACET.VALUE_KEY)
                .from(ITEM_FACET)
                .where(ITEM_FACET.ITEM_ID.eq(itemId), ITEM_FACET.FACET.eq(facet))
                .orderBy(ITEM_FACET.POSITION)
                .fetch { it.value1()!! }
        if (before != distinct.map { it.key }) {
            db.transaction { c ->
                val tx = DSL.using(c)
                tx.deleteFrom(ITEM_FACET).where(ITEM_FACET.ITEM_ID.eq(itemId), ITEM_FACET.FACET.eq(facet)).execute()
                distinct.forEachIndexed { i, v ->
                    tx.insertInto(ITEM_FACET)
                        .set(ITEM_FACET.ITEM_ID, itemId)
                        .set(ITEM_FACET.FACET, facet)
                        .set(ITEM_FACET.VALUE_KEY, v.key.take(VALUE_KEY_LENGTH))
                        .set(ITEM_FACET.POSITION, i)
                        .execute()
                }
            }
            changed(listOf(itemId))
        }
    }

    override fun keepOnlyFacets(facets: Collection<String>): Map<String, Int> {
        val gone =
            db.select(ITEM_FACET.FACET)
                .from(ITEM_FACET)
                .where(ITEM_FACET.FACET.notIn(facets))
                .fetch(ITEM_FACET.FACET)
                .groupingBy { it!! }
                .eachCount()
        if (gone.isNotEmpty()) db.deleteFrom(ITEM_FACET).where(ITEM_FACET.FACET.notIn(facets)).execute()
        db.deleteFrom(FACET_VALUE).where(FACET_VALUE.FACET.notIn(facets)).execute()
        db.deleteFrom(FACET_VALUE_DESCRIPTION).where(FACET_VALUE_DESCRIPTION.FACET.notIn(facets)).execute()
        return gone
    }

    override fun valueUses(facet: String): Map<String, Int> =
        db.select(ITEM_FACET.VALUE_KEY, DSL.count())
            .from(ITEM_FACET)
            .where(ITEM_FACET.FACET.eq(facet))
            .groupBy(ITEM_FACET.VALUE_KEY)
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    override fun facets(itemId: String): Map<String, List<String>> =
        db.select(ITEM_FACET.FACET, ITEM_FACET.VALUE_KEY)
            .from(ITEM_FACET)
            .where(ITEM_FACET.ITEM_ID.eq(itemId))
            .orderBy(ITEM_FACET.FACET, ITEM_FACET.POSITION)
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })

    override fun facetsOf(ids: Collection<String>): Map<String, Map<String, List<String>>> {
        val asked = ids.toHashSet()
        return if (asked.size <= ITEM_BATCH) facetsByIds(asked) else facetsStreamed(asked)
    }

    /** The facets of the items [asked], by their ids in one query: item to facet to keys in order. */
    private fun facetsByIds(asked: Set<String>): Map<String, Map<String, List<String>>> {
        val out = HashMap<String, MutableMap<String, MutableList<String>>>()
        db.select(ITEM_FACET.ITEM_ID, ITEM_FACET.FACET, ITEM_FACET.VALUE_KEY)
            .from(ITEM_FACET)
            .where(ITEM_FACET.ITEM_ID.`in`(asked))
            .orderBy(ITEM_FACET.ITEM_ID, ITEM_FACET.FACET, ITEM_FACET.POSITION)
            .fetch {
                out.getOrPut(it.value1()!!) { HashMap() }.getOrPut(it.value2()!!) { ArrayList() } += it.value3()!!
            }
        return out
    }

    /**
     * The facets of the items [asked], every value of every item streamed once and picked from, each list put in order
     * after: item to facet to keys in order.
     */
    private fun facetsStreamed(asked: Set<String>): Map<String, Map<String, List<String>>> {
        val placed = HashMap<String, MutableMap<String, MutableList<Pair<Int, String>>>>()
        db.select(ITEM_FACET.ITEM_ID, ITEM_FACET.FACET, ITEM_FACET.VALUE_KEY, ITEM_FACET.POSITION)
            .from(ITEM_FACET)
            .fetchSize(STREAMED_ROWS)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach {
                    val id = it.value1()!!
                    if (id in asked)
                        placed.getOrPut(id) { HashMap() }.getOrPut(it.value2()!!) { ArrayList() } +=
                            it.value4()!! to it.value3()!!
                }
            }
        val out = HashMap<String, MutableMap<String, MutableList<String>>>()
        placed.forEach { (id, facets) ->
            out[id] =
                facets.mapValuesTo(HashMap()) { (_, keys) ->
                    keys.sortedBy { it.first }.mapTo(ArrayList()) { it.second }
                }
        }
        return out
    }

    override fun itemsWith(facet: String, keys: Collection<String>): Map<String, List<String>> {
        if (keys.isEmpty()) return emptyMap()
        return db.select(ITEM_FACET.VALUE_KEY, ITEM_FACET.ITEM_ID)
            .from(ITEM_FACET)
            .where(ITEM_FACET.FACET.eq(facet), ITEM_FACET.VALUE_KEY.`in`(keys.distinct()))
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })
    }

    override fun forEachFacet(facet: String, action: (itemId: String, keys: List<String>) -> Unit) {
        var current: String? = null
        val list = ArrayList<String>()
        db.select(ITEM_FACET.ITEM_ID, ITEM_FACET.VALUE_KEY)
            .from(ITEM_FACET)
            .where(ITEM_FACET.FACET.eq(facet))
            .orderBy(ITEM_FACET.ITEM_ID, ITEM_FACET.POSITION)
            .fetchSize(STREAMED_ROWS)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach {
                    if (it.value1() != current) {
                        current?.let { id -> action(id, list.toList()) }
                        list.clear()
                        current = it.value1()
                    }
                    list += it.value2()!!
                }
            }
        current?.let { action(it, list.toList()) }
    }

    override fun forEachFacetValue(action: (itemId: String, facet: String, key: String) -> Unit) {
        db.select(ITEM_FACET.ITEM_ID, ITEM_FACET.FACET, ITEM_FACET.VALUE_KEY)
            .from(ITEM_FACET)
            .fetchSize(STREAMED_ROWS)
            .fetchLazy()
            .use { cursor -> cursor.forEach { action(it.value1()!!, it.value2()!!, it.value3()!!) } }
    }
}
