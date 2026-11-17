package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.database.model.tables.references.MODEL_VALUE
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE VALUES THE MODEL GIVES THE WORKS of one source, of every facet it works out, beyond the site's own
 * ([ModelValues.of]), in the model database of its content type: what the filters of the list read, no model run at a
 * request (user, 2026-11-17). Written by every pass of the models of the source's facets ([KeptModelValues]), of the
 * works whose values changed alone.
 */
class ModelValueRepository(private val db: DSLContext, private val source: String) {

    /**
     * The values of the [facet] the model gives the works [ids] — every work when null: item to its values, none of a
     * work it gives none. Of the works named, by their ids a [BATCH] at a time.
     */
    fun of(facet: String, ids: Collection<String>? = null): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        val chunks = ids?.distinct()?.chunked(BATCH) ?: listOf(null)
        chunks.forEach { chunk ->
            db.select(MODEL_VALUE.ITEM_ID, MODEL_VALUE.VALUE_KEY)
                .from(MODEL_VALUE)
                .where(MODEL_VALUE.SOURCE.eq(source), MODEL_VALUE.FACET.eq(facet))
                .and(if (chunk == null) DSL.noCondition() else MODEL_VALUE.ITEM_ID.`in`(chunk))
                .fetchSize(FETCH)
                .fetchLazy()
                .use { cursor -> cursor.forEach { out.getOrPut(it.value1()!!) { ArrayList() } += it.value2()!! } }
        }
        return out
    }

    /**
     * The values of the [facet] of the works [values] names replaced by these — a work with none left with none — a
     * transaction a [BATCH] of works.
     */
    fun replace(facet: String, values: Map<String, Collection<String>>) =
        values.entries.chunked(BATCH).forEach { chunk ->
            db.transaction { c ->
                val t = c.dsl()
                t.deleteFrom(MODEL_VALUE)
                    .where(
                        MODEL_VALUE.SOURCE.eq(source),
                        MODEL_VALUE.FACET.eq(facet),
                        MODEL_VALUE.ITEM_ID.`in`(chunk.map { it.key }),
                    )
                    .execute()
                val rows = chunk.flatMap { (id, keys) -> keys.map { id to it } }
                if (rows.isNotEmpty()) {
                    // One statement prepared and bound a row each, not each row's statement parsed apart
                    val batch =
                        t.batch(
                            t.insertInto(
                                    MODEL_VALUE,
                                    MODEL_VALUE.SOURCE,
                                    MODEL_VALUE.ITEM_ID,
                                    MODEL_VALUE.FACET,
                                    MODEL_VALUE.VALUE_KEY,
                                )
                                .values(null as String?, null as String?, null as String?, null as String?)
                        )
                    rows.forEach { (id, key) -> batch.bind(source, id, facet, key.take(KEY_WIDTH)) }
                    batch.execute()
                }
            }
        }

    /** The values of the facets no longer worked out forgotten: of every facet but [facets]. */
    fun keepOnly(facets: Collection<String>) {
        db.deleteFrom(MODEL_VALUE)
            .where(MODEL_VALUE.SOURCE.eq(source), MODEL_VALUE.FACET.notIn(facets.ifEmpty { listOf("") }))
            .execute()
    }

    /** The values of the item removed from the library. */
    fun forget(itemId: String) {
        db.deleteFrom(MODEL_VALUE).where(MODEL_VALUE.SOURCE.eq(source), MODEL_VALUE.ITEM_ID.eq(itemId)).execute()
    }

    private companion object {
        /** The works a transaction of [replace] and a statement of the ids it deletes of. */
        const val BATCH = 1000

        /** The rows a reading takes from the database at a time. */
        const val FETCH = 10_000

        /** The width of the column of a value's key (VARCHAR(500)): a longer one is cut. */
        const val KEY_WIDTH = 500
    }
}
