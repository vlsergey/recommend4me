package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.database.SourceItems
import io.github.vlsergey.recommend4me.database.model.tables.references.PREDICTION
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** An item's score on 0..10, and its chance of interest, 0..1, when there is a model of interest. */
class Predicted(val score: Double, val interest: Double?)

/** The prediction of every item of a content type by its current models, in its model database. */
class PredictionRepository(private val db: DSLContext) {

    /**
     * The predictions of a portion of the items written where they changed — a score or a chance of interest beyond
     * [STEP], an item new: most of a retraining's scores come out as they were, and a file rewritten whole on every
     * training only grows. The stored ones of the portion alone read, by their keys. Answers the items whose prediction
     * was written.
     */
    fun write(predicted: List<Pair<ItemKey, Predicted>>): List<ItemKey> {
        fun same(a: Double?, b: Double?) = if (a == null || b == null) a == b else kotlin.math.abs(a - b) < STEP
        val stored = HashMap<ItemKey, Predicted>()
        predicted.groupBy({ it.first.source }, { it.first.id }).forEach { (source, ids) ->
            ids.chunked(BATCH).forEach { chunk ->
                predicted(source, chunk).forEach { (id, p) -> stored[ItemKey(source, id)] = p }
            }
        }
        val changed = predicted.filter { (key, p) ->
            stored[key]?.let { same(it.score, p.score) && same(it.interest, p.interest) } != true
        }
        // One statement prepared and bound a row each: every row's own statement parsed by the database apart was
        // seconds of the training when the scores moved
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
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
        return changed.map { it.first }
    }

    /** The sources of the items predicted. */
    fun sources(): List<String> = db.selectDistinct(PREDICTION.SOURCE).from(PREDICTION).fetch { it.value1()!! }

    /**
     * The ids of the items of the [source] predicted, [count] of them after the id [after] (from the first when null)
     * in their order: every prediction looked over a page at a time.
     */
    fun idsAfter(source: String, after: String?, count: Int): List<String> =
        db.select(PREDICTION.ITEM_ID)
            .from(PREDICTION)
            .where(PREDICTION.SOURCE.eq(source), after?.let { PREDICTION.ITEM_ID.gt(it) } ?: DSL.noCondition())
            .orderBy(PREDICTION.ITEM_ID)
            .limit(count)
            .fetch { it.value1()!! }

    /** The predictions of the items [ids] of the [source] removed: of items gone from the type. */
    fun remove(source: String, ids: Collection<String>) =
        ids.chunked(BATCH).forEach { chunk ->
            db.deleteFrom(PREDICTION)
                .where(SourceItems.of(PREDICTION.SOURCE, PREDICTION.ITEM_ID, source, chunk))
                .execute()
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

    /**
     * The predictions of the items [ids] of a source with their chances of interest: item to both — by the key, a
     * [BATCH] of them a query, never every prediction of the source for many (a million of ficbook at every portion).
     */
    fun predicted(source: String, ids: Collection<String>): Map<String, Predicted> =
        ids.distinct().chunked(BATCH).fold(HashMap()) { out, chunk ->
            db.select(PREDICTION.ITEM_ID, PREDICTION.SCORE, PREDICTION.INTEREST)
                .from(PREDICTION)
                .where(SourceItems.of(PREDICTION.SOURCE, PREDICTION.ITEM_ID, source, chunk))
                .fetch { out[it.value1()!!] = Predicted(it.value2()!!, it.value3()) }
            out
        }

    companion object {
        private const val BATCH = 1000

        /** The rows fetched from the database at a time: memory, not meaning. */
        private const val FETCH = 5000

        /**
         * A score or a chance changed by less is not written again: a retraining's noise, unseen on the 0..10 scale. A
         * prediction kept may so stand that much above the score made of it.
         */
        const val STEP = 1e-4
    }
}
