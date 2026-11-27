package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.database.model.tables.references.MODEL_AUTO_CHANGES
import kotlin.math.ceil
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** The changes of no word of the user's counted beside a model since it was fitted, and of how many works it learnt. */
class AutoChangesCounted(val changes: Int, val learnt: Int?)

/**
 * THE CHANGES OF NO WORD OF THE USER'S A MODEL LEARNS FROM, COUNTED BESIDE IT, in the model database of the type
 * (owner, 2026-11-24): a chapter of a work the user rated, its texts' or its sets' vectors, its pictures — each a
 * change too small to fit the model again, and every work's values made anew after it. The model is fitted again once
 * they come to [SHARE] of the works it learnt of; the fitting lets go of those it read. The user's word fits it at
 * once, counted nowhere.
 */
class ModelAutoChanges(private val db: DSLContext) {

    fun of(model: String): AutoChangesCounted? =
        db.select(MODEL_AUTO_CHANGES.CHANGES, MODEL_AUTO_CHANGES.LEARNT)
            .from(MODEL_AUTO_CHANGES)
            .where(MODEL_AUTO_CHANGES.MODEL.eq(model))
            .fetchOne { AutoChangesCounted(it.value1()!!, it.value2()) }

    /**
     * [changes] more counted beside the [model]; whether it is to be fitted again now: they came to [SHARE] of the
     * works it learnt of, or it never told of how many.
     */
    fun count(model: String, changes: Int): Boolean = db.transactionResult { c ->
        val updated =
            c.dsl()
                .update(MODEL_AUTO_CHANGES)
                .set(MODEL_AUTO_CHANGES.CHANGES, MODEL_AUTO_CHANGES.CHANGES.plus(changes))
                .where(MODEL_AUTO_CHANGES.MODEL.eq(model))
                .execute()
        if (updated == 0)
            c.dsl()
                .insertInto(MODEL_AUTO_CHANGES)
                .set(MODEL_AUTO_CHANGES.MODEL, model)
                .set(MODEL_AUTO_CHANGES.CHANGES, changes)
                .execute()
        val counted =
            c.dsl()
                .select(MODEL_AUTO_CHANGES.CHANGES, MODEL_AUTO_CHANGES.LEARNT)
                .from(MODEL_AUTO_CHANGES)
                .where(MODEL_AUTO_CHANGES.MODEL.eq(model))
                .fetchOne { AutoChangesCounted(it.value1()!!, it.value2()) }!!
        counted.changes >= due(counted.learnt)
    }

    /** The [model] fitted of [learnt] works; the changes counted kept. */
    fun learnt(model: String, learnt: Int?) {
        db.insertInto(MODEL_AUTO_CHANGES)
            .set(MODEL_AUTO_CHANGES.MODEL, model)
            .set(MODEL_AUTO_CHANGES.LEARNT, learnt)
            .onDuplicateKeyUpdate()
            .set(MODEL_AUTO_CHANGES.LEARNT, learnt)
            .execute()
    }

    /** The [changes] a fitting of the [model] read let go: those counted while it ran stay. */
    fun letGo(model: String, changes: Int) {
        db.update(MODEL_AUTO_CHANGES)
            .set(
                MODEL_AUTO_CHANGES.CHANGES,
                DSL.greatest(MODEL_AUTO_CHANGES.CHANGES.minus(changes), DSL.inline(0)),
            )
            .where(MODEL_AUTO_CHANGES.MODEL.eq(model))
            .execute()
    }

    companion object {
        /** The models of the grades and of interest of the type. */
        const val GRADES = "grades"

        /** The words the manners of the type's texts are measured by. */
        const val MANNER = "manner"

        /** The share of the works a model learnt of the changes of no word of the user's come to: fitted again then. */
        private const val SHARE = 0.01

        /** The models of the fields of the [source]. */
        fun fields(source: String) = "fields:$source"

        /** How many changes fit a model learnt of [learnt] works again: [SHARE] of them; nought of none told. */
        fun due(learnt: Int?): Int = ceil((learnt ?: 0) * SHARE).toInt()
    }
}
