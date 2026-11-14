package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * THE LIKENESS OF THE NAMES OF THE SITES' FANDOMS, LEARNT OF THE USER'S WORD ON THE UNIVERSES (user, 2026-11-14): the
 * cosine of two names' vectors with every dimension weighed ([weights], none below nought) — the encoder's own of
 * weights all 1. A name's vector made ready for it ([of]): every number times the root of its weight, of unit length
 * again — the cosine of two such is the weighed one.
 *
 * Learnt ([fit]) of the universes as the user made them ([NamePairs]): two names of one universe — the sites' fandoms
 * the user bound together — and the names of a universe and of one under or above it likelier than a name of another;
 * the names of two universes the user said different the least alike of all. The weights pulled toward 1, the encoder's
 * likeness, as far as the word says nothing. On the card when there is one ([FandomLikenessOnCard]), else on the
 * processor ([FandomLikenessDescent]), alike within the last digits.
 */
class FandomLikeness(val weights: FloatArray) {
    private val roots = FloatArray(weights.size) { sqrt(weights[it]) }

    /** The vector [v] of a name made ready: weighed, of unit length; a vector of another length as it is. */
    fun of(v: FloatArray): FloatArray {
        if (v.size != weights.size) return v
        val out = FloatArray(v.size) { v[it] * roots[it] }
        var n = 0.0
        for (x in out) n += x.toDouble() * x
        n = sqrt(n)
        if (n > 0) for (k in out.indices) out[k] = (out[k] / n).toFloat()
        return out
    }

    fun pack(): ByteArray {
        val buffer = ByteBuffer.allocate(weights.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(weights)
        return buffer.array()
    }

    /** The card and the kernels of the likeness compiled for it. */
    class OnCard(val cublas: Cublas, val kernels: CudaKernels)

    companion object {
        fun unpack(bytes: ByteArray): FandomLikeness {
            val out = FloatArray(bytes.size / Float.SIZE_BYTES)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return FandomLikeness(out)
        }

        /** The weights learnt of the [pairs] of the names' [rows]; null of none. On the [card], under its lock. */
        fun fit(pairs: NamePairs, rows: HalfMatrix, card: OnCard?): FandomLikeness? =
            if (pairs.size == 0) null
            else
                FandomLikeness(
                    card?.let { c ->
                        c.cublas.lock.withLock { FandomLikenessOnCard(c, pairs, rows).use { it.run() } }
                    } ?: FandomLikenessDescent(pairs, rows).run()
                )

        /**
         * The share of the [pairs] of the names' [rows] the [likeness] — the encoder's own when null — puts in the
         * user's order; NaN of none.
         */
        fun ordered(pairs: NamePairs, rows: HalfMatrix, likeness: FandomLikeness?, card: OnCard?): Double {
            if (pairs.size == 0) return Double.NaN
            val w = likeness?.weights ?: FloatArray(rows.cols) { 1f }
            return card?.let { c ->
                c.cublas.lock.withLock { FandomLikenessOnCard(c, pairs, rows).use { it.ordered(w) } }
            } ?: FandomLikenessDescent(pairs, rows).ordered(w)
        }
    }
}
