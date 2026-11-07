package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.database.model.tables.references.HELD_OUT
import io.github.vlsergey.recommend4me.database.model.tables.references.HELD_OUT_EXPLAINED
import io.github.vlsergey.recommend4me.database.model.tables.references.MODEL
import io.github.vlsergey.recommend4me.database.model.tables.references.PREDICTION
import io.github.vlsergey.recommend4me.item.ItemKey
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A kept model: the scorer that made it, its description and its state. */
class StoredModel(val scorer: String, val meta: String, val weights: ByteArray)

/**
 * The current models of a content type — of the grades and of interest — in its model database; what they said of an
 * item ([PredictionRepository], [HeldOutRepository]) forgotten with them.
 */
class ModelRepository(private val db: DSLContext) {

    fun save(scorer: String, trainedAt: Instant, meta: String, weights: ByteArray) =
        save(CURRENT, scorer, trainedAt, meta, weights)

    fun load(): StoredModel? = load(CURRENT)

    fun saveInterest(scorer: String, trainedAt: Instant, meta: String, weights: ByteArray) =
        save(INTEREST, scorer, trainedAt, meta, weights)

    fun loadInterest(): StoredModel? = load(INTEREST)

    /** The model of interest, when there are too few marks of either kind to train one. */
    fun forgetInterest() {
        db.deleteFrom(MODEL).where(MODEL.ID.eq(INTEREST)).execute()
    }

    /**
     * A model saved; of the grades, what the models that had not seen the graded works said of them goes: they were of
     * the model before.
     */
    private fun save(id: Int, scorer: String, trainedAt: Instant, meta: String, weights: ByteArray) {
        db.transaction { c ->
            val tx = DSL.using(c)
            tx.insertInto(MODEL)
                .set(MODEL.ID, id)
                .set(MODEL.SCORER, scorer)
                .set(MODEL.TRAINED_AT, trainedAt)
                .set(MODEL.META, meta)
                .set(MODEL.WEIGHTS, weights)
                .onDuplicateKeyUpdate()
                .set(MODEL.SCORER, scorer)
                .set(MODEL.TRAINED_AT, trainedAt)
                .set(MODEL.META, meta)
                .set(MODEL.WEIGHTS, weights)
                .execute()
            if (id == CURRENT) tx.deleteFrom(HELD_OUT_EXPLAINED).execute()
        }
    }

    private fun load(id: Int): StoredModel? =
        db.select(MODEL.SCORER, MODEL.META, MODEL.WEIGHTS).from(MODEL).where(MODEL.ID.eq(id)).fetchOne {
            StoredModel(it.value1()!!, it.value2()!!, it.value3()!!)
        }

    /** Both models and every prediction. */
    fun forget() {
        db.transaction { c ->
            DSL.using(c).deleteFrom(MODEL).execute()
            DSL.using(c).deleteFrom(PREDICTION).execute()
            DSL.using(c).deleteFrom(HELD_OUT).execute()
            DSL.using(c).deleteFrom(HELD_OUT_EXPLAINED).execute()
        }
    }

    /** The predictions of the item removed from the library: the model's, and the one that had not seen it. */
    fun forget(key: ItemKey) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.deleteFrom(PREDICTION).where(PREDICTION.SOURCE.eq(key.source), PREDICTION.ITEM_ID.eq(key.id)).execute()
        tx.deleteFrom(HELD_OUT).where(HELD_OUT.SOURCE.eq(key.source), HELD_OUT.ITEM_ID.eq(key.id)).execute()
        tx.deleteFrom(HELD_OUT_EXPLAINED)
            .where(HELD_OUT_EXPLAINED.SOURCE.eq(key.source), HELD_OUT_EXPLAINED.ITEM_ID.eq(key.id))
            .execute()
    }

    companion object {
        private const val CURRENT = 1
        private const val INTEREST = 2
    }
}
