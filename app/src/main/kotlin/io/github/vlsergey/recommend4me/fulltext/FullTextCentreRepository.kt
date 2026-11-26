package io.github.vlsergey.recommend4me.fulltext

import io.github.vlsergey.recommend4me.database.model.tables.references.FULL_TEXT_CENTRE
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Random
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorOperators
import kotlin.math.sqrt
import org.jooq.DSLContext

/**
 * The centre of the windows' vectors of a type learnt ([FullTextCentres]) and its random [rotation]: its [key], kept
 * with every text whose full-text vectors it made, the [centre] itself, the rotation's rows, when and of how many
 * vectors.
 */
class FullTextCentre(
    val key: String,
    val centre: FloatArray,
    val rotation: Array<FloatArray>,
    val fittedAt: Instant,
    val windows: Int,
    /** How many windows the type had as it was learnt: learnt anew of twice as many ([FullTextCentres.read]). */
    val corpus: Long,
) {
    /**
     * The vector [v] less the centre, turned by the rotation: every row's product by the lanes of the processor's
     * vectors, summed in another order than one by one — the last digits alone moved (a tenth of the time a chapter
     * took, done number after number, 2026-11-26).
     */
    fun turned(v: FloatArray): FloatArray {
        val less = FloatArray(centre.size) { v[it] - centre[it] }
        val bound = LANES.loopBound(less.size)
        return FloatArray(rotation.size) { k ->
            val row = rotation[k]
            var sums = FloatVector.zero(LANES)
            var i = 0
            while (i < bound) {
                sums = FloatVector.fromArray(LANES, row, i).fma(FloatVector.fromArray(LANES, less, i), sums)
                i += LANES.length()
            }
            var s = sums.reduceLanes(VectorOperators.ADD)
            while (i < less.size) {
                s += row[i] * less[i]
                i++
            }
            s
        }
    }

    companion object {
        /** The lanes of the processor's vectors of numbers. */
        private val LANES = FloatVector.SPECIES_PREFERRED

        /**
         * A random rotation of [d] numbers: the rows of a matrix of numbers drawn of a normal law, made orthonormal one
         * after another (Gram-Schmidt) — what RaBitQ turns the vectors by before their bits are taken.
         */
        fun rotation(d: Int, draw: Random): Array<FloatArray> {
            val rows = ArrayList<DoubleArray>()
            repeat(d) {
                val v = DoubleArray(d) { draw.nextGaussian() }
                rows.forEach { r ->
                    val p = (0 until d).sumOf { v[it] * r[it] }
                    for (i in 0 until d) v[i] -= p * r[i]
                }
                val n = sqrt(v.sumOf { it * it })
                rows += DoubleArray(d) { v[it] / n }
            }
            return Array(d) { k -> FloatArray(d) { rows[k][it].toFloat() } }
        }
    }
}

/** The centre of the windows' vectors of a type, of every encoder of windows one, in the type's model database. */
class FullTextCentreRepository(private val db: DSLContext) {

    /** The centre of the [encoder]; none of none, nor of one learnt before the rotations were (2026-11-25). */
    fun of(encoder: String): FullTextCentre? =
        db.selectFrom(FULL_TEXT_CENTRE)
            .where(FULL_TEXT_CENTRE.ENCODER.eq(encoder), FULL_TEXT_CENTRE.ROTATION.isNotNull)
            .fetchOne { r ->
                val centre = floats(r.centre!!)
                val rotation = floats(r.rotation!!)
                FullTextCentre(
                    r.centreKey!!,
                    centre,
                    Array(rotation.size / centre.size) { k ->
                        rotation.copyOfRange(k * centre.size, (k + 1) * centre.size)
                    },
                    r.fittedAt!!,
                    r.windows!!,
                    r.corpus ?: r.windows!!.toLong(),
                )
            }

    /** The [centre] of the [encoder] kept in place of any before. */
    fun keep(encoder: String, centre: FullTextCentre) {
        db.transaction { c ->
            c.dsl().deleteFrom(FULL_TEXT_CENTRE).where(FULL_TEXT_CENTRE.ENCODER.eq(encoder)).execute()
            c.dsl()
                .insertInto(FULL_TEXT_CENTRE)
                .set(FULL_TEXT_CENTRE.ENCODER, encoder)
                .set(FULL_TEXT_CENTRE.CENTRE_KEY, centre.key)
                .set(FULL_TEXT_CENTRE.FITTED_AT, centre.fittedAt)
                .set(FULL_TEXT_CENTRE.CENTRE, bytes(listOf(centre.centre)))
                .set(FULL_TEXT_CENTRE.ROTATION, bytes(centre.rotation.toList()))
                .set(FULL_TEXT_CENTRE.WINDOWS, centre.windows)
                .set(FULL_TEXT_CENTRE.CORPUS, centre.corpus)
                .execute()
        }
    }

    private fun floats(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { b.getFloat() }
    }

    private fun bytes(rows: List<FloatArray>): ByteArray {
        val b = ByteBuffer.allocate(rows.sumOf { it.size } * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        rows.forEach { r -> r.forEach(b::putFloat) }
        return b.array()
    }
}
