package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Simd
import io.github.vlsergey.recommend4me.matrix.logisticRow

/**
 * THE NAMES AS TEXTS OF A WORK ([Problem.hints]) OF EVERY MODEL OF AN ENSEMBLE ([Ensemble]), on the processor: their
 * coordinates of every model by one product; their loss a group of them at a time — the group's names' and values'
 * coordinates gathered, their scores by one product, the slopes back by two; their slopes carried back to the text
 * blocks' weights by one product more. Of the ensemble's numbers, of [count] models of rank [r], read: the weights A
 * ([aT]), the values' coordinates [vT] and the biases [bias]; written: the slopes of A ([gA]), of E ([gE]), of the
 * biases ([gBias]) and of the pair's own weights ([gPair]).
 */
internal class NamesOfEnsemble(
    private val p: Problem,
    count: Int,
    private val r: Int,
    private val aT: Floats,
    private val vT: Floats,
    private val bias: Floats,
    private val gA: Floats,
    private val gE: Floats,
    private val gBias: Floats,
    private val gPair: Floats,
) {
    private val dx = p.design.itemWidth
    private val m = p.m

    /** How many names there are. */
    val h = p.hints.size
    private val hd = p.hintDim
    private val sumT = if (h > 0) Floats(count * r * hd) else Floats(0)

    /** The names' coordinates of every model, transposed: rank rows of every name; and their slopes. */
    val uhT = if (h > 0) Floats(count * r * h) else Floats(0)
    val duhT = if (h > 0) Floats(count * r * h) else Floats(0)
    private val dSumT = if (h > 0) Floats(count * r * hd) else Floats(0)

    // A group's names and values gathered, their scores and slopes: made once, as large as the largest group
    private val largest = p.hintGroups.maxOfOrNull { it.rows.size } ?: 0
    private val widest = p.hintGroups.maxOfOrNull { it.values.size } ?: 0
    private val gu = Floats(r * largest)
    private val gv = Floats(r * widest)
    private val gs = Floats(largest * widest)
    private val gdu = Floats(r * largest)
    private val gdv = Floats(r * widest)
    private val columns = Floats(widest)

    /** A column of ones, as long as the largest group: the sums of a group's columns it makes. */
    private val ones = Floats(maxOf(largest, 1)).also { o -> for (k in 0 until o.count) o[k] = 1f }

    /** A group's values' biases with the common one, and the two sums of a row: made once. */
    private val shift = FloatArray(widest)
    private val sums = FloatArray(2)

    /**
     * The names' coordinates of the first [k] models: Uhᵀ = (Σ_b s_b A_bᵀ) Hᵀ + A_constᵀ + Σ_b A_mark(b)ᵀ — every text
     * block of theirs holds the name, so the text blocks' weights summed meet it once. Their slopes made nought.
     */
    fun coordinates(k: Int) {
        sumT.clear(0, k * r * hd)
        for (slot in 0 until k) for (q in 0 until r) {
            val rowA = (slot * r + q) * dx
            val rowSum = (slot * r + q) * hd
            p.hintBlocks.forEach { b ->
                Simd.axpy(p.design.blocks[b].scale, aT, rowA + p.design.blockOffsets[b] + 1, sumT, rowSum, hd)
            }
        }
        Blas.gemm(false, true, k * r, h, hd, sumT, 0, hd, p.hintText.held, 0, hd, uhT, 0, h)
        for (slot in 0 until k) for (q in 0 until r) {
            val rowA = (slot * r + q) * dx
            var base = aT[rowA]
            p.hintBlocks.forEach { b -> base += aT[rowA + p.design.blockOffsets[b]] }
            Simd.add(base, uhT, (slot * r + q) * h, h)
        }
        duhT.clear(0, k * r * h)
    }

    /**
     * The loss of every name of the model in [slot] read as a text that names its value and none of its rivals, weight
     * 1 a pair, a group of them at a time: the group's names' and values' coordinates gathered, their scores by one
     * product, the slopes back by two; the loss summed.
     */
    fun loss(slot: Int, scale: Float, common: Float): Float {
        val uhAt = slot * r * h
        val vAt = slot * r * m
        val biasAt = slot * m
        val pairAt = slot * Bilinear.PAIR
        var loss = 0f
        var gCommon = 0f
        p.hintGroups.forEach { group ->
            val kRows = group.rows.size
            val c = group.values.size
            for (q in 0 until r) {
                for (row in 0 until kRows) gu[q * kRows + row] = uhT[uhAt + q * h + group.rows[row]]
                for (j in 0 until c) gv[q * c + j] = vT[vAt + q * m + group.values[j]]
            }
            for (j in 0 until c) shift[j] = bias[biasAt + group.values[j]] + common
            // The scores of the group's every name against its every value
            Blas.gemm(true, false, kRows, c, r, gu, 0, kRows, gv, 0, c, gs, 0, c)
            // Every value of a row against the label nought at once; the name's own value then set
            // right: its label is one
            for (row in 0 until kRows) {
                val own = group.positive[row]
                val at = row * c
                val before = if (own in 0 until c) gs[at + own] + shift[own] else 0f
                sums[0] = 0f
                sums[1] = 0f
                Simd.logisticRow(gs, at, shift, c, scale, sums)
                if (own in 0 until c) {
                    // softplus(z) − z for the label one in place of softplus(z) for nought
                    val d = (Bilinear.sigmoid(before) - 1) * scale
                    sums[0] -= before
                    sums[1] += d - Bilinear.sigmoid(before) * scale
                    gs[at + own] = d
                }
                loss += sums[0]
                gCommon += sums[1]
            }
            // The slopes of the values' biases: the columns summed
            Blas.gemv(true, kRows, c, gs, 0, c, ones, 0, columns, 0)
            for (j in 0 until c) gBias[biasAt + group.values[j]] = gBias[biasAt + group.values[j]] + columns[j]
            // The gradient of the group's coordinates, scattered back: of the names dUhᵀ = Vᵀ Sᵀ, of the values dVᵀ =
            // Uhᵀ S
            Blas.gemm(false, true, r, kRows, c, gv, 0, c, gs, 0, c, gdu, 0, kRows)
            Blas.gemm(false, false, r, c, kRows, gu, 0, kRows, gs, 0, c, gdv, 0, c)
            for (q in 0 until r) {
                for (row in 0 until kRows) {
                    val at = uhAt + q * h + group.rows[row]
                    duhT[at] = duhT[at] + gdu[q * kRows + row]
                }
                for (j in 0 until c) {
                    val at = vAt + q * m + group.values[j]
                    gE[at] = gE[at] + gdv[q * c + j]
                }
            }
        }
        gPair[pairAt + 1] = gPair[pairAt + 1] + gCommon
        return loss
    }

    /**
     * The names' slopes carried back to the text blocks' weights, the constant's and the marks': of the first [k]
     * models, one product.
     */
    fun gradient(k: Int) {
        Blas.gemm(false, false, k * r, hd, h, duhT, 0, h, p.hintText.held, 0, hd, dSumT, 0, hd)
        for (slot in 0 until k) for (q in 0 until r) {
            val rowA = (slot * r + q) * dx
            val rowSum = (slot * r + q) * hd
            val base = Simd.sum(duhT, (slot * r + q) * h, h)
            gA[rowA] = gA[rowA] + base
            p.hintBlocks.forEach { b ->
                val mark = rowA + p.design.blockOffsets[b]
                gA[mark] = gA[mark] + base
                Simd.axpy(p.design.blocks[b].scale, dSumT, rowSum, gA, mark + 1, hd)
            }
        }
    }
}
