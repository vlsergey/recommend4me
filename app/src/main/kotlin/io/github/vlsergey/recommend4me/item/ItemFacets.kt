package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.WholeReadRefused
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_DESCRIPTION
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_FACET
import io.github.vlsergey.recommend4me.source.FacetValue
import org.jooq.Condition
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

    /**
     * Whether any item has a value of the facet: one row of its index asked — of ficbook's kinds, not a count of 1.4
     * million values at every scoring of a facet ([valueUses]; 2026-11-19, half a second a page of the extension).
     */
    fun anyOf(facet: String): Boolean

    /** How many items have each of the [keys] of the facet: key to items, a key of none left out. */
    fun valueUsesOf(facet: String, keys: Collection<String>): Map<String, Int>

    /** The facets of one item: facet to keys in order ([facetsOf] of it alone). */
    fun facets(itemId: String): Map<String, List<String>> = facetsOf(listOf(itemId))[itemId].orEmpty()

    /**
     * The facets of the items [ids]: item to facet to keys in order. By their ids, a batch of them ([ITEM_BATCH]) a
     * query, up to a portion of a scoring ([BY_IDS_UP_TO]); of more, every value of every item streamed once and picked
     * from, each list put in order after: by thousands of ids the values of 31 000 works of 75 000 took 5 s — but a
     * pass over the values of a million works for every portion of ten thousand is a hundred passes (2026-11-17).
     */
    fun facetsOf(ids: Collection<String>): Map<String, Map<String, List<String>>>

    /** The items the site gives each of the [keys] of the facet: key to items. */
    fun itemsWith(facet: String, keys: Collection<String>): Map<String, List<String>>

    /** Every item's keys of one facet, item by item, in no order of the site's. */
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
        // The key of every value and its name as the page wrote it (user, 2026-11-18: "ideally a work keeps both — the
        // tags and the names"): a value given with no name keeps the one it had
        val before =
            db.select(ITEM_FACET.VALUE_KEY, ITEM_FACET.NAME)
                .from(ITEM_FACET)
                .where(ITEM_FACET.ITEM_ID.eq(itemId), ITEM_FACET.FACET.eq(facet))
                .orderBy(ITEM_FACET.POSITION)
                .fetch { it.value1()!! to it.value2() }
        val was = before.toMap()
        val after = distinct.map { it.key to (it.name?.take(NAME_LENGTH) ?: was[it.key]) }
        if (before == after) return
        db.transaction { c ->
            val tx = DSL.using(c)
            tx.deleteFrom(ITEM_FACET).where(ITEM_FACET.ITEM_ID.eq(itemId), ITEM_FACET.FACET.eq(facet)).execute()
            after.forEachIndexed { i, (key, name) ->
                tx.insertInto(ITEM_FACET)
                    .set(ITEM_FACET.ITEM_ID, itemId)
                    .set(ITEM_FACET.FACET, facet)
                    .set(ITEM_FACET.VALUE_KEY, key.take(VALUE_KEY_LENGTH))
                    .set(ITEM_FACET.NAME, name)
                    .set(ITEM_FACET.POSITION, i)
                    .execute()
            }
        }
        // A name the site wrote anew is no change of the work: its values are the same
        if (before.map { it.first } != after.map { it.first }) changed(listOf(itemId))
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
        WholeReadRefused.check("every value of the facet $facet")
            .let { _ -> db }
            .select(ITEM_FACET.VALUE_KEY, DSL.count())
            .from(ITEM_FACET)
            .where(ITEM_FACET.FACET.eq(facet))
            .groupBy(ITEM_FACET.VALUE_KEY)
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    override fun anyOf(facet: String): Boolean = db.fetchExists(ITEM_FACET, ITEM_FACET.FACET.eq(facet))

    override fun valueUsesOf(facet: String, keys: Collection<String>): Map<String, Int> =
        keys
            .distinct()
            .chunked(ITEM_BATCH)
            .flatMap { chunk ->
                db.select(ITEM_FACET.VALUE_KEY, DSL.count())
                    .from(ITEM_FACET)
                    .where(valuesOf(facet, chunk))
                    .groupBy(ITEM_FACET.VALUE_KEY)
                    .fetch { it.value1()!! to it.value2()!! }
            }
            .toMap()

    override fun facetsOf(ids: Collection<String>): Map<String, Map<String, List<String>>> {
        val asked = ids.toHashSet()
        return if (asked.size > BY_IDS_UP_TO) facetsStreamed(asked)
        else
            HashMap<String, Map<String, List<String>>>().apply {
                asked.chunked(ITEM_BATCH).forEach { putAll(facetsByIds(it.toSet())) }
            }
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
        return keys
            .distinct()
            .chunked(ITEM_BATCH)
            .flatMap { chunk ->
                db.select(ITEM_FACET.VALUE_KEY, ITEM_FACET.ITEM_ID)
                    .from(ITEM_FACET)
                    .where(valuesOf(facet, chunk))
                    .fetch { it.value1()!! to it.value2()!! }
            }
            .groupBy({ it.first }, { it.second })
    }

    override fun forEachFacet(facet: String, action: (itemId: String, keys: List<String>) -> Unit) {
        // One pass of the facet's index, no order asked of the database: grouped by item here (user, 2026-11-18)
        val keys = HashMap<String, MutableList<String>>()
        db.select(ITEM_FACET.ITEM_ID, ITEM_FACET.VALUE_KEY)
            .from(ITEM_FACET)
            .where(ITEM_FACET.FACET.eq(facet))
            .fetchSize(STREAMED_ROWS)
            .fetchLazy()
            .use { cursor -> cursor.forEach { keys.getOrPut(it.value1()!!) { ArrayList(1) } += it.value2()!! } }
        keys.forEach { (id, own) -> action(id, own) }
    }

    override fun forEachFacetValue(action: (itemId: String, facet: String, key: String) -> Unit) {
        db.select(ITEM_FACET.ITEM_ID, ITEM_FACET.FACET, ITEM_FACET.VALUE_KEY)
            .from(ITEM_FACET)
            .fetchSize(STREAMED_ROWS)
            .fetchLazy()
            .use { cursor -> cursor.forEach { action(it.value1()!!, it.value2()!!, it.value3()!!) } }
    }
}

/**
 * The rows of the values [keys] of the [facet], by the index of the facet and the value: a row of both a value, as
 * [io.github.vlsergey.recommend4me.database.SourceItems] — H2 takes `facet = ? AND value_key IN (…)` by the facet alone
 * and walks its every value, every author of every work of a site (2026-11-20: 22 s of a page of 30 authors).
 */
private fun valuesOf(facet: String, keys: Collection<String>): Condition =
    DSL.row(ITEM_FACET.FACET, ITEM_FACET.VALUE_KEY)
        .`in`(keys.map { DSL.row(DSL.`val`(facet, ITEM_FACET.FACET), DSL.`val`(it, ITEM_FACET.VALUE_KEY)) })
