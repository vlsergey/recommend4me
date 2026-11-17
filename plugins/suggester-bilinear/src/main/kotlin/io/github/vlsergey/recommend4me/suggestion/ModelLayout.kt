package io.github.vlsergey.recommend4me.suggestion

import java.util.Random
import kotlin.math.sqrt

/**
 * THE WEIGHTS OF ONE MODEL LAID OUT FLAT, as [Bilinear] reads them, of a [design] and a [rank]: A (itemWidth × rank), B
 * (valueWidth × rank), E (m × rank), the values' own biases and the pair's own weights — [size] numbers. How a fitting
 * starts them, how weights fitted on another layout are carried to this one, and how they are kept ([Weights]).
 */
internal class ModelLayout(val design: Design, val rank: Int) {
    private val dx = design.itemWidth
    private val dz = design.valueWidth
    private val m = design.valueKeys.size

    val size = dx * rank + dz * rank + m * rank + m + Bilinear.PAIR

    /**
     * The start: A and B nought, so a weight grows only where the data lead it — along a direction no item varies in
     * (titles alike) it stays nought, and an item unlike the rest there is not judged by a random weight; the own
     * coordinates of the values [used] — some pair of which weighs anything — small and random, for the gradient not to
     * be nought everywhere as at the very origin; the common bias at [common], the base rate of the pairs.
     */
    fun start(seed: Long, used: BooleanArray, common: Float): FloatArray {
        val random = Random(seed)
        val out = FloatArray(size)
        val eAt = dx * rank + dz * rank
        val se = START_SPREAD / sqrt(rank.toDouble())
        for (v in 0 until m) if (used[v])
            for (q in 0 until rank) out[eAt + v * rank + q] = (random.nextGaussian() * se).toFloat()
        out[size - 1] = common
        return out
    }

    /**
     * The weights [w] fitted on the layout [old], carried to this one: a feature or a value the old one had keeps its
     * weight, scaled to weigh as it did; a new one starts at nought — its gradient is not, the other side being learnt
     * already.
     */
    fun carried(w: Weights, old: Design): FloatArray {
        check(w.rank == rank) { "Weights of rank ${w.rank}, a model of rank $rank" }
        val out = FloatArray(size)
        val (items, itemFactor) = design.itemFeaturesFrom(old)
        for (f in 0 until dx) items[f]
            .takeIf { it >= 0 }
            ?.let { o -> for (q in 0 until rank) out[f * rank + q] = w.a[o * rank + q] * itemFactor[f] }
        val bAt = dx * rank
        val (values, valueFactor) = design.valueFeaturesFrom(old)
        for (f in 0 until dz) values[f]
            .takeIf { it >= 0 }
            ?.let { o -> for (q in 0 until rank) out[bAt + f * rank + q] = w.b[o * rank + q] * valueFactor[f] }
        val eAt = bAt + dz * rank
        val biasAt = eAt + m * rank
        design.valueKeys.forEachIndexed { v, key ->
            val o = old.valueIndex[key] ?: return@forEachIndexed
            for (q in 0 until rank) out[eAt + v * rank + q] = w.e[o * rank + q]
            out[biasAt + v] = w.bias[o]
        }
        w.pair.copyInto(out, size - Bilinear.PAIR)
        return out
    }

    /** The weights [theta] as kept. */
    fun weights(theta: FloatArray): Weights {
        fun part(from: Int, count: Int) = theta.copyOfRange(from, from + count)
        val bAt = dx * rank
        val eAt = bAt + dz * rank
        val biasAt = eAt + m * rank
        return Weights(
            rank,
            part(0, dx * rank),
            part(bAt, dz * rank),
            part(eAt, m * rank),
            part(biasAt, m),
            part(size - Bilinear.PAIR, Bilinear.PAIR),
        )
    }

    private companion object {
        /** The spread of a value's own coordinates at the start, over the root of the rank: small, not nought. */
        const val START_SPREAD = 0.1
    }
}
