package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_FACET
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_TEXT
import org.jooq.DSLContext
import org.jooq.impl.DSL

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

/** The [ItemMeasures] of the source database [db]. */
internal class ItemMeasureRepository(private val db: DSLContext) : ItemMeasures {

    override fun lengthsOf(ids: Collection<String>): Map<String, Long> =
        ids.distinct()
            .chunked(ITEM_BATCH)
            .flatMap { chunk ->
                db.select(ITEM_TEXT.ITEM_ID, DSL.sum(DSL.charLength(ITEM_TEXT.CONTENT)))
                    .from(ITEM_TEXT)
                    .where(ITEM_TEXT.ITEM_ID.`in`(chunk))
                    .groupBy(ITEM_TEXT.ITEM_ID)
                    .fetch { it.value1()!! to (it.value2()?.toLong() ?: 0L) }
            }
            .toMap()

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

    override fun withAnyOf(ids: Collection<String>, facet: String, keys: Collection<String>): Set<String> =
        if (keys.isEmpty()) emptySet()
        else
            ids.distinct().chunked(ITEM_BATCH).flatMapTo(HashSet()) { chunk ->
                db.selectDistinct(ITEM_FACET.ITEM_ID)
                    .from(ITEM_FACET)
                    .where(
                        ITEM_FACET.ITEM_ID.`in`(chunk),
                        ITEM_FACET.FACET.eq(facet),
                        ITEM_FACET.VALUE_KEY.`in`(keys.distinct()),
                    )
                    .fetch { it.value1()!! }
            }
}
