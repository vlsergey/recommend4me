package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_NUMBER
import org.jooq.DSLContext

/** THE NUMBERS THE SITE GIVES ITS ITEMS — by their keys, as the source database holds them. */
interface ItemNumbers {
    /** The numbers of the item written; a null or a NaN one removed. */
    fun setNumbers(itemId: String, numbers: Map<String, Double?>)

    /** The numbers of one item: key to value. */
    fun numbers(itemId: String): Map<String, Double>

    /** The numbers of the items [ids]: item to key to value. */
    fun numbersOf(ids: Collection<String>): Map<String, Map<String, Double>>

    /** Every number of every item, streamed: (item, key, value). */
    fun forEachNumber(action: (itemId: String, key: String, value: Double) -> Unit)

    /** One number of every item that has it. */
    fun number(key: String): Map<String, Double>
}

/** The [ItemNumbers] of the source database [db]. */
internal class ItemNumberRepository(private val db: DSLContext) : ItemNumbers {

    override fun setNumbers(itemId: String, numbers: Map<String, Double?>) {
        numbers.forEach { (key, value) ->
            if (value == null || value.isNaN()) {
                db.deleteFrom(ITEM_NUMBER)
                    .where(ITEM_NUMBER.ITEM_ID.eq(itemId), ITEM_NUMBER.NUMBER_KEY.eq(key))
                    .execute()
            } else {
                db.insertInto(ITEM_NUMBER)
                    .set(ITEM_NUMBER.ITEM_ID, itemId)
                    .set(ITEM_NUMBER.NUMBER_KEY, key)
                    .set(ITEM_NUMBER.CONTENT, value)
                    .onDuplicateKeyUpdate()
                    .set(ITEM_NUMBER.CONTENT, value)
                    .execute()
            }
        }
    }

    override fun numbers(itemId: String): Map<String, Double> =
        db.select(ITEM_NUMBER.NUMBER_KEY, ITEM_NUMBER.CONTENT)
            .from(ITEM_NUMBER)
            .where(ITEM_NUMBER.ITEM_ID.eq(itemId))
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

    override fun numbersOf(ids: Collection<String>): Map<String, Map<String, Double>> {
        val out = HashMap<String, MutableMap<String, Double>>()
        ids.distinct().chunked(ITEM_BATCH).forEach { chunk ->
            db.select(ITEM_NUMBER.ITEM_ID, ITEM_NUMBER.NUMBER_KEY, ITEM_NUMBER.CONTENT)
                .from(ITEM_NUMBER)
                .where(ITEM_NUMBER.ITEM_ID.`in`(chunk))
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }[it.value2()!!] = it.value3()!! }
        }
        return out
    }

    override fun forEachNumber(action: (itemId: String, key: String, value: Double) -> Unit) {
        db.select(ITEM_NUMBER.ITEM_ID, ITEM_NUMBER.NUMBER_KEY, ITEM_NUMBER.CONTENT)
            .from(ITEM_NUMBER)
            .fetchSize(STREAMED_ROWS)
            .fetchLazy()
            .use { cursor -> cursor.forEach { action(it.value1()!!, it.value2()!!, it.value3()!!) } }
    }

    override fun number(key: String): Map<String, Double> =
        db.select(ITEM_NUMBER.ITEM_ID, ITEM_NUMBER.CONTENT)
            .from(ITEM_NUMBER)
            .where(ITEM_NUMBER.NUMBER_KEY.eq(key))
            .fetch()
            .associate { it.value1()!! to it.value2()!! }
}
