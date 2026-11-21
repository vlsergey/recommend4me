package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.database.SourceItems
import io.github.vlsergey.recommend4me.database.model.tables.references.PREDICTION
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.worklist.ListDocuments
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** An item's score on 0..10, and its chance of interest, 0..1, when there is a model of interest. */
class Predicted(val score: Double, val interest: Double?)

/** The prediction of every item of a content type by its current models, in its model database. */
class PredictionRepository(private val db: DSLContext) {

    /**
     * The predictions of a portion of the items written where they changed — a score or a chance of interest beyond
     * [STEP], an item new: most of a retraining's scores come out as they were, and a file rewritten whole on every
     * training only grows. The stored ones of the portion alone read, by their keys. Of those whose prediction as the
     * index of the list keeps it ([ListDocuments.kept]) is another, their date written with it ([changedSince]): the
     * index writes those alone. Answers the items whose prediction was written.
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
        val (shown, quiet) = changed.partition { (key, p) -> stored[key]?.let { kept(it) == kept(p) } != true }
        val now = Instant.now()
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            merged(tx, shown, now)
            merged(tx, quiet, null)
        }
        return changed.map { it.first }
    }

    /**
     * The [rows] written, within the transaction [tx]; changed as the index of the list keeps them [at] — their date
     * kept as it is, of none. One statement prepared and bound a row each: every row's own statement parsed by the
     * database apart was seconds of the training when the scores moved.
     */
    private fun merged(tx: DSLContext, rows: List<Pair<ItemKey, Predicted>>, at: Instant?) {
        val p = PREDICTION
        val columns = listOfNotNull(p.SOURCE, p.ITEM_ID, p.SCORE, p.INTEREST, p.CHANGED_AT.takeIf { at != null })
        rows.chunked(BATCH).forEach { chunk ->
            val batch =
                tx.batch(tx.mergeInto(p).columns(columns).key(p.SOURCE, p.ITEM_ID).values(List(columns.size) { null }))
            // The chance of interest null of no model of interest: bound as it is
            chunk.forEach { (key, v) ->
                val values = arrayListOf<Any?>(key.source, key.id, v.score, v.interest)
                if (at != null) values += at
                batch.bind(*values.toTypedArray())
            }
            batch.execute()
        }
    }

    /**
     * The items whose predictions changed as the index of the list keeps them at [since] or after, by the index of the
     * dates: source to its items.
     */
    fun changedSince(since: Instant): Map<String, List<String>> =
        db.select(PREDICTION.SOURCE, PREDICTION.ITEM_ID)
            .from(PREDICTION)
            .where(PREDICTION.CHANGED_AT.ge(since))
            .fetchGroups({ it.value1()!! }, { it.value2()!! })

    /** Whether a prediction changed as the index of the list keeps it at [since] or after. */
    fun anySince(since: Instant): Boolean = db.fetchExists(PREDICTION, PREDICTION.CHANGED_AT.ge(since))

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

        /** The prediction as the index of the list keeps it: its score and its chance, to its digits. */
        private fun kept(p: Predicted) = ListDocuments.kept(p.score) to ListDocuments.kept(p.interest)

        /** The rows fetched from the database at a time: memory, not meaning. */
        private const val FETCH = 5000

        /**
         * A score or a chance changed by less is not written again: a retraining's noise, unseen on the 0..10 scale. A
         * prediction kept may so stand that much above the score made of it.
         */
        const val STEP = 1e-4
    }
}
