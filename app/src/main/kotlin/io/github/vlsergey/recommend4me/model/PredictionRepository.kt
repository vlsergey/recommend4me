package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.database.model.tables.references.PREDICTION
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** An item's score on 0..10, and its chance of interest, 0..1, when there is a model of interest. */
class Predicted(val score: Double, val interest: Double?)

/** The prediction of every item of a content type by its current models, in its model database. */
class PredictionRepository(private val db: DSLContext) {

    /**
     * Replaces every item's prediction in one transaction: readers see the old ones until the new ones are committed;
     * nothing else writes this table. Only what changed is written — a score or a chance of interest beyond [STEP], an
     * item new or gone: most of a retraining's scores come out as they were, and a file rewritten whole on every
     * training only grows.
     */
    fun replace(predicted: List<Pair<ItemKey, Predicted>>) {
        fun same(a: Double?, b: Double?) = if (a == null || b == null) a == b else kotlin.math.abs(a - b) < STEP
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            val stored = HashMap<ItemKey, Pair<Double?, Double?>>()
            tx.select(PREDICTION.SOURCE, PREDICTION.ITEM_ID, PREDICTION.SCORE, PREDICTION.INTEREST)
                .from(PREDICTION)
                .fetchSize(FETCH)
                .fetchLazy()
                .use { cursor ->
                    cursor.forEach { stored[ItemKey(it.value1()!!, it.value2()!!)] = it.value3() to it.value4() }
                }
            val wanted = predicted.toMap()
            val gone = stored.keys - wanted.keys
            gone.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
                ids.chunked(BATCH).forEach { chunk ->
                    tx.deleteFrom(PREDICTION)
                        .where(PREDICTION.SOURCE.eq(source), PREDICTION.ITEM_ID.`in`(chunk))
                        .execute()
                }
            }
            val changed = predicted.filter { (key, p) ->
                stored[key]?.let { (score, interest) -> same(score, p.score) && same(interest, p.interest) } != true
            }
            // One statement prepared and bound a row each: every row's own statement parsed by the database apart was
            // seconds of the training when the scores moved
            changed.chunked(BATCH).forEach { chunk ->
                val batch =
                    tx.batch(
                        tx.mergeInto(PREDICTION)
                            .columns(PREDICTION.SOURCE, PREDICTION.ITEM_ID, PREDICTION.SCORE, PREDICTION.INTEREST)
                            .key(PREDICTION.SOURCE, PREDICTION.ITEM_ID)
                            .values(null as String?, null as String?, null as Double?, null as Double?)
                    )
                chunk.forEach { (key, p) -> batch.bind(key.source, key.id, p.score, p.interest) }
                batch.execute()
            }
        }
    }

    fun save(key: ItemKey, p: Predicted) {
        db.insertInto(PREDICTION)
            .set(PREDICTION.SOURCE, key.source)
            .set(PREDICTION.ITEM_ID, key.id)
            .set(PREDICTION.SCORE, p.score)
            .set(PREDICTION.INTEREST, p.interest)
            .onDuplicateKeyUpdate()
            .set(PREDICTION.SCORE, p.score)
            .set(PREDICTION.INTEREST, p.interest)
            .execute()
    }

    /** Every prediction of a source's items: item to score. */
    fun scores(source: String): Map<String, Double> =
        db.select(PREDICTION.ITEM_ID, PREDICTION.SCORE)
            .from(PREDICTION)
            .where(PREDICTION.SOURCE.eq(source))
            .fetchSize(FETCH)
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

    /** Every prediction of a source's items with its chance of interest: item to both. */
    fun predicted(source: String): Map<String, Predicted> =
        db.select(PREDICTION.ITEM_ID, PREDICTION.SCORE, PREDICTION.INTEREST)
            .from(PREDICTION)
            .where(PREDICTION.SOURCE.eq(source))
            .fetchSize(FETCH)
            .fetch()
            .associate { it.value1()!! to Predicted(it.value2()!!, it.value3()) }

    companion object {
        private const val BATCH = 1000

        /** The rows fetched from the database at a time: memory, not meaning. */
        private const val FETCH = 5000

        /**
         * A score or a chance changed by less is not written again: a retraining's noise, unseen on the 0..10 scale.
         */
        private const val STEP = 1e-4
    }
}
