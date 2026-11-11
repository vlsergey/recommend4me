package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_VALUE
import org.jooq.DSLContext

/**
 * HOW RARE EVERY VALUE TWO WORKS ARE COMPARED BY IS, as the last search of duplicates weighed it among every work of
 * the type ([TypeSearch.rarity]), by the value's name: a pair the search did not keep is compared by them as it is
 * shown ([LoosePairs]), the same as the search compares one. Made anew whole by every search, with its candidates.
 */
class ValueRarities(private val db: DSLContext) {

    /** Every value's rarity in place of those before, written by [tx]: the last search's let go by a truncation. */
    fun replace(tx: DSLContext, rarity: Map<String, Float>) {
        tx.truncate(DUPLICATE_VALUE).execute()
        rarity.entries.chunked(BATCH).forEach { chunk ->
            val batch =
                tx.batch(
                    tx.insertInto(DUPLICATE_VALUE, DUPLICATE_VALUE.NAME, DUPLICATE_VALUE.RARITY)
                        .values(null as String?, null as Float?)
                )
            chunk.forEach { (value, r) -> batch.bind(value, r) }
            batch.execute()
        }
    }

    /** The rarity of the [values] the last search weighed: a value it did not meet, none. */
    fun of(values: Collection<String>): Map<String, Float> {
        val out = HashMap<String, Float>()
        values.distinct().chunked(BATCH).forEach { chunk ->
            db.select(DUPLICATE_VALUE.NAME, DUPLICATE_VALUE.RARITY)
                .from(DUPLICATE_VALUE)
                .where(DUPLICATE_VALUE.NAME.`in`(chunk))
                .fetch { out[it.value1()!!] = it.value2()!! }
        }
        return out
    }

    private companion object {
        const val BATCH = 1000
    }
}
