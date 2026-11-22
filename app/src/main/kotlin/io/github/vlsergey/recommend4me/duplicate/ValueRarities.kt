package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.database.duplicates.tables.references.DUPLICATE_VALUE
import org.duckdb.DuckDBConnection
import org.jooq.DSLContext

/**
 * HOW RARE EVERY VALUE TWO WORKS ARE COMPARED BY IS, as the last search of duplicates weighed it among every work of
 * the type ([TypeSearch.rarity]), by the value's name: a pair the search did not keep is compared by them as it is
 * shown ([LoosePairs]), the same as the search compares one. Made anew whole by every search, with its candidates.
 */
class ValueRarities(private val db: DSLContext) {

    /** Every value's rarity in place of those before, in the transaction of [tx], appended by its [connection]. */
    fun replace(connection: DuckDBConnection, tx: DSLContext, rarity: Map<String, Float>) {
        tx.deleteFrom(DUPLICATE_VALUE).execute()
        connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, DUPLICATE_VALUE.name.lowercase()).use { a ->
            rarity.forEach { (value, r) ->
                a.beginRow()
                a.append(value)
                a.append(r)
                a.endRow()
            }
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
