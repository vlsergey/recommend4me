package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Simd
import io.github.vlsergey.recommend4me.matrix.logisticRow
import java.util.stream.IntStream

/**
 * THE NAMES AS TEXTS OF A WORK ([Problem.hints]) in a model's fitting on the processor ([Bilinear]): every text block
 * of theirs holds the name, so their coordinates are the name times the text blocks' weights summed, plus the
 * constant's and the blocks' marks'. Their loss on the processor needs room for a group's every pair — a large
 * universe's a hundred megabytes: made only when the processor is the one to take it, never with a card ([StepOnCard]).
 */
internal class NamesAsTexts(private val p: Problem, private val rank: Int) {
    private val m = p.m

    /** How many names there are, and the width of their texts' features. */
    val h = p.hints.size
    private val hd = p.hintDim
    private val uh by lazy { Floats(h * rank) }
    private val duh by lazy { Floats(h * rank) }
    private val aSum by lazy { Floats(hd * rank) }
    private val dSum by lazy { Floats(hd * rank) }
    private val hintBase = FloatArray(rank)

    /** The names' slopes carried to the values' coordinates (m × rank), of the last [loss]. */
    val dvHint by lazy { FloatArray(m * rank) }
    private val largest = p.hintGroups.maxOfOrNull { it.rows.size } ?: 0
    private val widest = p.hintGroups.maxOfOrNull { it.values.size } ?: 0
    private val gu by lazy { Floats(largest * rank) }
    private val gv by lazy { Floats(widest * rank) }
    private val gvt by lazy { Floats(rank * widest) }
    private val gs by lazy { Floats(largest * widest) }
    private val gdu by lazy { Floats(largest * rank) }
    private val gdv by lazy { Floats(widest * rank) }
    /** A column of ones, and the sums of a group's columns it makes. */
    private val ones by lazy { Floats.of(FloatArray(largest) { 1f }) }
    private val columns by lazy { Floats(widest) }

    /**
     * The coordinates of every name read as a work's every text, of the weights A in [aF]: Uh = H·Σ_b s_b·A_b +
     * A_const + Σ_b A_mark(b).
     */
    fun coordinates(aF: Floats) {
        aSum.clear()
        hintBase.fill(0f)
        for (q in 0 until rank) hintBase[q] = aF[q]
        p.hintBlocks.forEach { b ->
            val mark = p.design.blockOffsets[b]
            val scale = p.design.blocks[b].scale
            for (q in 0 until rank) hintBase[q] += aF[mark * rank + q]
            for (c in 0 until hd) for (q in 0 until rank) aSum[c * rank + q] =
                aSum[c * rank + q] + scale * aF[(mark + 1 + c) * rank + q]
        }
        Blas.product(p.hintText.held, hd, h, hd, aSum, rank, uh)
        for (k in 0 until h) for (q in 0 until rank) uh[k * rank + q] = uh[k * rank + q] + hintBase[q]
    }

    /**
     * The loss of every name read as a text that names its value and none of its rivals, weight 1 a pair, against the
     * values' coordinates [v] and the biases at [bias] of [theta]; its gradient into [duh], [dvHint] and the biases'
     * slopes [gBias], the slope of the common bias into [common]. Answers the loss summed, not averaged.
     */
    fun loss(
        theta: FloatArray,
        pair: FloatArray,
        bias: Int,
        v: Floats,
        total: Float,
        gBias: FloatArray,
        common: FloatArray,
    ): Float {
        duh.clear()
        dvHint.fill(0f)
        var loss = 0f
        p.hintGroups.forEach { group ->
            val k = group.rows.size
            val c = group.values.size
            // The group's names' and values' coordinates, gathered; their scores by one product
            group.rows.forEachIndexed { r, row -> for (q in 0 until rank) gu[r * rank + q] = uh[row * rank + q] }
            group.values.forEachIndexed { j, value ->
                for (q in 0 until rank) {
                    gv[j * rank + q] = v[value * rank + q]
                    gvt[q * c + j] = v[value * rank + q]
                }
            }
            Blas.product(gu, rank, k, rank, gvt, c, gs)
            // Every pair's loss and slope, a row of names at a time on every core: a large universe
            // has thousands of names against thousands of characters. Every value of the row is
            // taken against the label nought at once, in the wide registers; the name's own value
            // is then set right: its label is one
            val shift = FloatArray(c) { j -> theta[bias + group.values[j]] + pair[1] }
            val scale = 1 / total
            val rowLoss = FloatArray(k)
            val rowSlope = FloatArray(k)
            // The scores are taken where they lie, in the native memory of the product: not copied out and back
            IntStream.range(0, k).parallel().forEach { r ->
                val own = group.positive[r]
                val at = r * c
                val before = if (own in 0 until c) gs[at + own] + shift[own] else 0f
                val sums = FloatArray(2)
                Simd.logisticRow(gs, at, shift, c, scale, sums)
                if (own in 0 until c) {
                    // softplus(z) − z for the label one in place of softplus(z) for nought
                    val d = (Bilinear.sigmoid(before) - 1) / total
                    sums[0] -= before
                    sums[1] += d - Bilinear.sigmoid(before) / total
                    gs[at + own] = d
                }
                rowLoss[r] = sums[0]
                rowSlope[r] = sums[1]
            }
            loss += rowLoss.sum()
            common[0] += rowSlope.sum()
            // The slope of every value's bias: the sum of its column, by one product with a column of ones
            Blas.transposedProduct(gs, c, k, c, ones, 1, columns)
            for (j in 0 until c) gBias[group.values[j]] += columns[j]
            // The gradient of the group's coordinates, scattered back
            Blas.product(gs, c, k, c, gv, rank, gdu)
            Blas.transposedProduct(gs, c, k, c, gu, rank, gdv)
            group.rows.forEachIndexed { r, row ->
                for (q in 0 until rank) duh[row * rank + q] = duh[row * rank + q] + gdu[r * rank + q]
            }
            group.values.forEachIndexed { j, value ->
                for (q in 0 until rank) dvHint[value * rank + q] += gdv[j * rank + q]
            }
        }
        return loss
    }

    /** The gradient of the names' coordinates of the last [loss] carried back into the weights, added to [da]. */
    fun addGradientOfLoss(da: FloatArray) {
        Blas.transposedProduct(p.hintText.held, hd, h, hd, duh, rank, dSum)
        val base = FloatArray(rank)
        for (k in 0 until h) for (q in 0 until rank) base[q] += duh[k * rank + q]
        addGradient(da, FloatArray(hd * rank).also { dSum.take(it, 0, 0, hd * rank) }, base)
    }

    /**
     * The gradient of the names' coordinates carried back into the text blocks' weights, the constant's and the marks',
     * added to [da]: of Hᵀ dUh ([sum], hintDim × rank) and of Σ dUh ([base]).
     */
    fun addGradient(da: FloatArray, sum: FloatArray, base: FloatArray) {
        for (q in 0 until rank) da[q] += base[q]
        p.hintBlocks.forEach { b ->
            val mark = p.design.blockOffsets[b]
            val scale = p.design.blocks[b].scale
            for (q in 0 until rank) da[mark * rank + q] += base[q]
            for (c in 0 until hd) for (q in 0 until rank) da[(mark + 1 + c) * rank + q] += scale * sum[c * rank + q]
        }
    }
}
