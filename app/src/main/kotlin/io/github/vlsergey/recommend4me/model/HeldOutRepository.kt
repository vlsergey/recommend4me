package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.database.model.tables.references.HELD_OUT
import io.github.vlsergey.recommend4me.database.model.tables.references.HELD_OUT_EXPLAINED
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * What the model that had not seen a graded work says of it, as kept: no [score] when there were too few works for
 * folds; the [explanation] as JSON.
 */
class Explained(val score: Double?, val gradePlace: Double?, val explanation: String?)

/**
 * WHAT THE MODELS THAT HAD NOT SEEN THE GRADED WORKS SAY OF THEM, in the model database of a content type: the score of
 * every graded item by the cross-validation, and what is worked out of one when a page asks for it.
 */
class HeldOutRepository(private val db: DSLContext) {

    /** What the model that had not seen the graded work says of it, as worked out; null while it is not. */
    fun explained(key: ItemKey): Explained? =
        db.select(HELD_OUT_EXPLAINED.SCORE, HELD_OUT_EXPLAINED.GRADE_PLACE, HELD_OUT_EXPLAINED.EXPLANATION)
            .from(HELD_OUT_EXPLAINED)
            .where(HELD_OUT_EXPLAINED.SOURCE.eq(key.source), HELD_OUT_EXPLAINED.ITEM_ID.eq(key.id))
            .fetchOne { Explained(it.value1(), it.value2(), it.value3()) }

    /**
     * Keeps what the model that had not seen the graded work says of it: its [score], none for a work with too few
     * works for folds.
     */
    fun keepExplained(key: ItemKey, score: Double?, gradePlace: Double?, explanation: String?) {
        db.insertInto(HELD_OUT_EXPLAINED)
            .set(HELD_OUT_EXPLAINED.SOURCE, key.source)
            .set(HELD_OUT_EXPLAINED.ITEM_ID, key.id)
            .set(HELD_OUT_EXPLAINED.SCORE, score)
            .set(HELD_OUT_EXPLAINED.GRADE_PLACE, gradePlace)
            .set(HELD_OUT_EXPLAINED.EXPLANATION, explanation)
            .onDuplicateKeyUpdate()
            .set(HELD_OUT_EXPLAINED.SCORE, score)
            .set(HELD_OUT_EXPLAINED.GRADE_PLACE, gradePlace)
            .set(HELD_OUT_EXPLAINED.EXPLANATION, explanation)
            .execute()
    }

    /** What was worked out of the graded work goes: a word on it changed what it is made of. */
    fun forgetExplained(key: ItemKey) {
        db.deleteFrom(HELD_OUT_EXPLAINED)
            .where(HELD_OUT_EXPLAINED.SOURCE.eq(key.source), HELD_OUT_EXPLAINED.ITEM_ID.eq(key.id))
            .execute()
    }

    /** Replaces the predictions of the graded items by the models that had not seen them, in one transaction. */
    fun replace(scores: Map<ItemKey, Double>) {
        db.transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.deleteFrom(HELD_OUT).execute()
            scores.entries.chunked(BATCH).forEach { chunk ->
                val batch =
                    tx.batch(
                        tx.insertInto(HELD_OUT, HELD_OUT.SOURCE, HELD_OUT.ITEM_ID, HELD_OUT.SCORE)
                            .values(null as String?, null as String?, null as Double?)
                    )
                chunk.forEach { (key, score) -> batch.bind(key.source, key.id, score) }
                batch.execute()
            }
        }
    }

    /** Every prediction of a source's graded items by the model that had not seen them: item to score. */
    fun scores(source: String): Map<String, Double> =
        db.select(HELD_OUT.ITEM_ID, HELD_OUT.SCORE).from(HELD_OUT).where(HELD_OUT.SOURCE.eq(source)).fetch().associate {
            it.value1()!! to it.value2()!!
        }

    private companion object {
        const val BATCH = 1000
    }
}
