package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.model.tables.references.FANDOM_LIKENESS
import java.time.Instant
import org.jooq.DSLContext

/** How the likeness of the fandoms' names was learnt: of how many pairs, how right on the held out, and the encoder. */
class FandomLikenessFit(val pairs: Int, val heldOut: Double?, val asEncoded: Double?)

/** The likeness of the names of the sites' fandoms learnt ([FandomLikeness]), in a model database: one of it. */
class FandomLikenessRepository(private val db: DSLContext) {

    /** The likeness learnt; none before the user's word taught any. */
    fun find(): FandomLikeness? =
        db.selectFrom(FANDOM_LIKENESS).orderBy(FANDOM_LIKENESS.FITTED_AT.desc()).limit(1).fetchOne {
            FandomLikeness.unpack(it.content!!)
        }

    /** The [likeness] kept in place of any before it, with how it was learnt. */
    fun keep(likeness: FandomLikeness, fit: FandomLikenessFit, now: Instant) {
        db.transaction { c ->
            c.dsl().deleteFrom(FANDOM_LIKENESS).execute()
            c.dsl()
                .insertInto(FANDOM_LIKENESS)
                .set(FANDOM_LIKENESS.ID, now.toEpochMilli())
                .set(FANDOM_LIKENESS.FITTED_AT, now)
                .set(FANDOM_LIKENESS.PAIRS, fit.pairs)
                .set(FANDOM_LIKENESS.HELD_OUT, fit.heldOut)
                .set(FANDOM_LIKENESS.AS_ENCODED, fit.asEncoded)
                .set(FANDOM_LIKENESS.CONTENT, likeness.pack())
                .execute()
        }
    }
}
