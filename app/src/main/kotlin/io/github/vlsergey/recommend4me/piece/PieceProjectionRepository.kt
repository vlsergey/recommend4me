package io.github.vlsergey.recommend4me.piece

import io.github.vlsergey.recommend4me.database.model.tables.references.PIECE_PROJECTION
import io.github.vlsergey.recommend4me.encoder.PieceProjection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import org.jooq.DSLContext

/**
 * The projection of the pieces learnt and the codes it is kept by: what the pieces of a type are made and read with.
 */
class FittedPieces(val projection: PieceProjection, val codebook: PieceCodebook, val fittedAt: Instant, val pieces: Int)

/** The projection of the pieces of a type, of every encoder of pieces one, in the type's model database. */
class PieceProjectionRepository(private val db: DSLContext) {

    fun of(encoder: String): FittedPieces? =
        db.selectFrom(PIECE_PROJECTION).where(PIECE_PROJECTION.ENCODER.eq(encoder)).fetchOne { r ->
            val rows = r.rowsCount!!
            val width = r.width!!
            val b = ByteBuffer.wrap(r.projection!!).order(ByteOrder.LITTLE_ENDIAN)
            FittedPieces(
                PieceProjection(r.projectionKey!!, List(rows) { FloatArray(width) { b.getFloat() } }),
                PieceCodebook.unpack(r.codebook!!, rows),
                r.fittedAt!!,
                r.pieces!!,
            )
        }

    /** The [fitted] projection of the [encoder] kept in place of any before. */
    fun keep(encoder: String, fitted: FittedPieces) {
        val rows = fitted.projection.rows
        val b = ByteBuffer.allocate(rows.size * rows[0].size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        rows.forEach { r -> r.forEach(b::putFloat) }
        db.transaction { c ->
            c.dsl().deleteFrom(PIECE_PROJECTION).where(PIECE_PROJECTION.ENCODER.eq(encoder)).execute()
            c.dsl()
                .insertInto(PIECE_PROJECTION)
                .set(PIECE_PROJECTION.ENCODER, encoder)
                .set(PIECE_PROJECTION.PROJECTION_KEY, fitted.projection.key)
                .set(PIECE_PROJECTION.FITTED_AT, fitted.fittedAt)
                .set(PIECE_PROJECTION.ROWS_COUNT, rows.size)
                .set(PIECE_PROJECTION.WIDTH, rows[0].size)
                .set(PIECE_PROJECTION.PROJECTION, b.array())
                .set(PIECE_PROJECTION.CODEBOOK, fitted.codebook.pack())
                .set(PIECE_PROJECTION.PIECES, fitted.pieces)
                .execute()
        }
    }
}
