package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Simd
import io.github.vlsergey.recommend4me.matrix.weightedLogisticRow

/**
 * THE PAIRS OF THE ITEMS AND THEIR VALUES OF AN ENSEMBLE ON THE PROCESSOR ([Ensemble], without a card for them): of a
 * model, a block of the items' rows against every value at a time, S = U Vᵀ; of the pairs that weigh anything the loss
 * and the slope, written over S, the rest nought; dU = S V, dV = Sᵀ U, the slopes of the biases its columns summed. Of
 * the ensemble's numbers, read: the items' coordinates [uT], the values' [vT], the biases [bias], the pair's own
 * weights [pair]; written: the items' slopes [duT], the values' [gE], the biases' [gBias] and the pair's own [gPair].
 */
internal class PairsOnProcessor(
    private val p: Problem,
    private val r: Int,
    private val heldOut: IntArray,
    private val uT: Floats,
    private val vT: Floats,
    private val bias: Floats,
    private val pair: Floats,
    private val duT: Floats,
    private val gE: Floats,
    private val gBias: Floats,
    private val gPair: Floats,
) {
    private val n = p.n
    private val m = p.m

    /** The rows of items a block of the pairs takes: a block of them against every value, held at once. */
    private val blockRows = minOf(n, maxOf(1, BLOCK / maxOf(1, m)))
    private val scores = Floats(blockRows * m)
    private val ones = Floats(maxOf(blockRows, 1)).also { o -> for (k in 0 until o.count) o[k] = 1f }

    // A row of the pairs that weigh anything, worked on in Java between the products: made once, as long as the longest
    // row
    private val longest = p.longest
    private val pairRow = p.row()
    private val z = FloatArray(longest)
    private val shift = FloatArray(longest)
    private val slopes = FloatArray(longest)
    private val sums = FloatArray(2)

    /**
     * The pairs of the model of the [slot] — the [model] of the ensemble — scaled by [scale], the common bias [common]:
     * a block of rows at a time. The loss summed, not averaged; the slopes of the pair's own weights into the gradient.
     */
    fun loss(slot: Int, model: Int, scale: Float, common: Float): Float {
        val uAt = slot * r * n
        val vAt = slot * r * m
        val biasAt = slot * m
        val pairAt = slot * Bilinear.PAIR
        val example = pair[pairAt]
        var loss = 0f
        var gCommon = 0f
        var gExample = 0f
        var from = 0
        while (from < n) {
            val rows = minOf(blockRows, n - from)
            // S = U Vᵀ of the block's rows: Uᵀ's columns of them against Vᵀ
            Blas.gemm(true, false, rows, m, r, uT, uAt + from, n, vT, vAt, m, scores, 0, m)
            for (b in 0 until rows) {
                val i = from + b
                val at = b * m
                val len = if (p.foldOf[i] == heldOut[model]) 0 else p.pairsOf(i, pairRow)
                if (len == 0) {
                    scores.clear(at, m)
                    continue
                }
                val values = pairRow.values
                for (j in 0 until len) {
                    z[j] = scores[at + values[j]] + example * pairRow.examples[j]
                    shift[j] = bias[biasAt + values[j]] + common
                }
                sums[0] = 0f
                sums[1] = 0f
                Simd.weightedLogisticRow(z, shift, pairRow.weights, pairRow.labels, len, scale, slopes, sums)
                loss += sums[0]
                gCommon += sums[1]
                for (j in 0 until len) gExample += slopes[j] * pairRow.examples[j]
                scores.clear(at, m)
                for (j in 0 until len) scores[at + values[j]] = slopes[j]
            }
            // dUᵀ of the block's rows = Vᵀ Sᵀ; dVᵀ += Uᵀ of them · S; the biases' slopes += Sᵀ 1
            Blas.gemm(false, true, r, rows, m, vT, vAt, m, scores, 0, m, duT, uAt + from, n)
            val first = if (from == 0) 0f else 1f
            Blas.gemm(false, false, r, m, rows, uT, uAt + from, n, scores, 0, m, gE, vAt, m, first)
            Blas.gemv(true, rows, m, scores, 0, m, ones, 0, gBias, biasAt, first)
            from += rows
        }
        gPair[pairAt] = gExample
        gPair[pairAt + 1] = gCommon
        return loss
    }

    companion object {
        /**
         * The scores of the pairs held at once, a block of the items' rows against every value: memory, not meaning.
         */
        const val BLOCK = 8 shl 20
    }
}
