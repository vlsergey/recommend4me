package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_FACET
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * WHAT THE SITE SAYS OF ITS ITEMS, MEASURED — the characters of their texts, the number of their values, whether they
 * have a value — counted by the source database, nothing of the texts or the values read: which of the items of a work
 * it is shown with ([io.github.vlsergey.recommend4me.work.Works]).
 */
interface ItemMeasures {
    /** The characters of all the texts of each of the items [ids]: item to characters. */
    fun lengthsOf(ids: Collection<String>): Map<String, Long>

    /** How many values of every facet together the site gives each of the items [ids]: item to values. */
    fun valueCountsOf(ids: Collection<String>): Map<String, Int>

    /** Those of the items [ids] the site gives any of the [keys] of the [facet]. */
    fun withAnyOf(ids: Collection<String>, facet: String, keys: Collection<String>): Set<String>
}

/** The [ItemMeasures] of the source database [db], whose works have the texts of the keys [texts] ([SiteTexts]). */
internal class ItemMeasureRepository(private val db: DSLContext, texts: Collection<String>) : ItemMeasures {

    /** The length of all the texts of an item's row together. */
    private val length =
        texts
            .map { DSL.coalesce(DSL.charLength(DSL.field(DSL.name(SiteTexts.columnOf(it)), SQLDataType.VARCHAR)), 0) }
            .reduceOrNull { a, b -> a.plus(b) }

    override fun lengthsOf(ids: Collection<String>): Map<String, Long> {
        val sum = length ?: return emptyMap()
        return ids.distinct()
            .chunked(ITEM_BATCH)
            .flatMap { chunk ->
                db.select(ITEM.SITE_ID, sum).from(ITEM).where(ITEM.SITE_ID.`in`(chunk)).and(sum.gt(0)).fetch {
                    it.value1()!! to (it.value2()?.toLong() ?: 0L)
                }
            }
            .toMap()
    }

    override fun valueCountsOf(ids: Collection<String>): Map<String, Int> =
        ids.distinct()
            .chunked(ITEM_BATCH)
            .flatMap { chunk ->
                db.select(ITEM_FACET.ITEM_ID, DSL.count())
                    .from(ITEM_FACET)
                    .where(ITEM_FACET.ITEM_ID.`in`(chunk))
                    .groupBy(ITEM_FACET.ITEM_ID)
                    .fetch { it.value1()!! to it.value2()!! }
            }
            .toMap()

    /**
     * Asked by the items alone — the first column of the table's key — and the facet's [keys] picked out of their
     * values here: a condition of the facet and its values lets H2 take the index of the values and test every item of
     * a value — every finished work of the site — against the list of the items, the work of minutes a batch of a
     * dump's import, more with every work it added (user, 2026-11-17).
     */
    override fun withAnyOf(ids: Collection<String>, facet: String, keys: Collection<String>): Set<String> {
        if (keys.isEmpty()) return emptySet()
        val wanted = keys.toHashSet()
        return ids.distinct().chunked(ITEM_BATCH).flatMapTo(HashSet()) { chunk ->
            db.select(ITEM_FACET.ITEM_ID, ITEM_FACET.FACET, ITEM_FACET.VALUE_KEY)
                .from(ITEM_FACET)
                .where(ITEM_FACET.ITEM_ID.`in`(chunk))
                .fetch()
                .filter { it.value2() == facet && it.value3() in wanted }
                .map { it.value1()!! }
        }
    }
}
