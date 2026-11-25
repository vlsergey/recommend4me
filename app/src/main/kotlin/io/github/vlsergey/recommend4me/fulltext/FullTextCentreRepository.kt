package io.github.vlsergey.recommend4me.fulltext

import io.github.vlsergey.recommend4me.database.model.tables.references.FULL_TEXT_CENTRE
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import org.jooq.DSLContext

/**
 * The centre of the windows' vectors of a type learnt ([FullTextCentres]): its [key], kept with every text whose
 * full-text vectors it made, the [centre] itself, when and of how many vectors.
 */
class FullTextCentre(val key: String, val centre: FloatArray, val fittedAt: Instant, val windows: Int)

/** The centre of the windows' vectors of a type, of every encoder of windows one, in the type's model database. */
class FullTextCentreRepository(private val db: DSLContext) {

    fun of(encoder: String): FullTextCentre? =
        db.selectFrom(FULL_TEXT_CENTRE).where(FULL_TEXT_CENTRE.ENCODER.eq(encoder)).fetchOne { r ->
            val b = ByteBuffer.wrap(r.centre!!).order(ByteOrder.LITTLE_ENDIAN)
            FullTextCentre(
                r.centreKey!!,
                FloatArray(r.centre!!.size / Float.SIZE_BYTES) { b.getFloat() },
                r.fittedAt!!,
                r.windows!!,
            )
        }

    /** The [centre] of the [encoder] kept in place of any before. */
    fun keep(encoder: String, centre: FullTextCentre) {
        val b = ByteBuffer.allocate(centre.centre.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        centre.centre.forEach(b::putFloat)
        db.transaction { c ->
            c.dsl().deleteFrom(FULL_TEXT_CENTRE).where(FULL_TEXT_CENTRE.ENCODER.eq(encoder)).execute()
            c.dsl()
                .insertInto(FULL_TEXT_CENTRE)
                .set(FULL_TEXT_CENTRE.ENCODER, encoder)
                .set(FULL_TEXT_CENTRE.CENTRE_KEY, centre.key)
                .set(FULL_TEXT_CENTRE.FITTED_AT, centre.fittedAt)
                .set(FULL_TEXT_CENTRE.CENTRE, b.array())
                .set(FULL_TEXT_CENTRE.WINDOWS, centre.windows)
                .execute()
        }
    }
}
