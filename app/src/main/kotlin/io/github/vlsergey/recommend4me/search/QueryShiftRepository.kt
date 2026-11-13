package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.database.model.tables.references.SEARCH_QUERY_SHIFT
import io.github.vlsergey.recommend4me.vector.Vectors
import java.time.Instant
import org.jooq.DSLContext

/** The correction of the closeness by meaning learnt ([QueryShift]), in the model database of the type. */
class QueryShiftRepository(private val db: DSLContext) {

    /** The correction learnt of the vectors of the [encoder], and when; null when none is. */
    fun read(encoder: String): Pair<QueryShift, Instant>? =
        db.selectFrom(SEARCH_QUERY_SHIFT).where(SEARCH_QUERY_SHIFT.ENCODER.eq(encoder)).fetchOne()?.let {
            QueryShift(Vectors.unpack(it.a!!), Vectors.unpack(it.b!!)) to it.fittedAt!!
        }

    /** The [shift] learnt now of the vectors of the [encoder] kept in place of any before; none kept of null. */
    fun keep(shift: QueryShift?, encoder: String, now: Instant) = db.transaction { c ->
        val tx = c.dsl()
        tx.deleteFrom(SEARCH_QUERY_SHIFT).execute()
        if (shift != null)
            tx.insertInto(SEARCH_QUERY_SHIFT)
                .set(SEARCH_QUERY_SHIFT.ENCODER, encoder)
                .set(SEARCH_QUERY_SHIFT.A, Vectors.pack(shift.a))
                .set(SEARCH_QUERY_SHIFT.B, Vectors.pack(shift.b))
                .set(SEARCH_QUERY_SHIFT.FITTED_AT, now)
                .execute()
    }
}
