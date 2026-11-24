package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.model.tables.references.FANDOM_LIKENESS
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * How the likeness of the fandoms' names was learnt: of how many pairs, of how many universes, how right on the held
 * out, and the encoder.
 */
class FandomLikenessFit(val pairs: Int, val universes: Int?, val heldOut: Double?, val asEncoded: Double?)

/**
 * The changes of no word of the user's counted since the likeness was learnt ([changes]), and of how many universes it
 * was learnt — null of a likeness learnt before they were told.
 */
class FandomLikenessChanges(val changes: Int, val universes: Int?)

/**
 * The likeness of the names of the sites' fandoms learnt ([FandomLikeness]), in a model database: one of it, with the
 * changes of no word of the user's counted since it was learnt.
 */
class FandomLikenessRepository(private val db: DSLContext) {

    /** The likeness learnt; none before the user's word taught any. */
    fun find(): FandomLikeness? =
        db.selectFrom(FANDOM_LIKENESS).orderBy(FANDOM_LIKENESS.FITTED_AT.desc()).limit(1).fetchOne {
            FandomLikeness.unpack(it.content!!)
        }

    /**
     * The [likeness] kept in place of any before it, with how it was learnt; the changes counted beside the one before
     * kept with it.
     */
    fun keep(likeness: FandomLikeness, fit: FandomLikenessFit, now: Instant) {
        db.transaction { c ->
            val counted = c.dsl().select(FANDOM_LIKENESS.AUTO_CHANGES).from(FANDOM_LIKENESS).fetchOne()?.value1()
            c.dsl().deleteFrom(FANDOM_LIKENESS).execute()
            c.dsl()
                .insertInto(FANDOM_LIKENESS)
                .set(FANDOM_LIKENESS.ID, now.toEpochMilli())
                .set(FANDOM_LIKENESS.FITTED_AT, now)
                .set(FANDOM_LIKENESS.PAIRS, fit.pairs)
                .set(FANDOM_LIKENESS.UNIVERSES, fit.universes)
                .set(FANDOM_LIKENESS.HELD_OUT, fit.heldOut)
                .set(FANDOM_LIKENESS.AS_ENCODED, fit.asEncoded)
                .set(FANDOM_LIKENESS.AUTO_CHANGES, counted ?: 0)
                .set(FANDOM_LIKENESS.CONTENT, likeness.pack())
                .execute()
        }
    }

    /** The changes of no word of the user's counted since the likeness was learnt; null of none learnt. */
    fun autoChanges(): Int? = db.select(FANDOM_LIKENESS.AUTO_CHANGES).from(FANDOM_LIKENESS).fetchOne()?.value1()

    /** [changes] of no word of the user's counted, and how many there are now; null of no likeness learnt. */
    fun count(changes: Int): FandomLikenessChanges? = db.transactionResult { c ->
        c.dsl()
            .update(FANDOM_LIKENESS)
            .set(FANDOM_LIKENESS.AUTO_CHANGES, FANDOM_LIKENESS.AUTO_CHANGES.plus(changes))
            .execute()
        c.dsl().select(FANDOM_LIKENESS.AUTO_CHANGES, FANDOM_LIKENESS.UNIVERSES).from(FANDOM_LIKENESS).fetchOne {
            FandomLikenessChanges(it.value1()!!, it.value2())
        }
    }

    /** The [changes] a learning read let go: those counted while it ran stay. */
    fun letGo(changes: Int) {
        db.update(FANDOM_LIKENESS)
            .set(FANDOM_LIKENESS.AUTO_CHANGES, DSL.greatest(FANDOM_LIKENESS.AUTO_CHANGES.minus(changes), DSL.inline(0)))
            .execute()
    }
}
