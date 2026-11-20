package io.github.vlsergey.recommend4me.size

import java.util.stream.IntStream

/**
 * The examples of the words to a character as rows of features, flat: every example's features one after another
 * ([features], the [offsets] of each example's first and of the end), its logarithm of the ratio ([y]) and the
 * characters it is of ([chars]); of [features] numbers the first of which, the mean, every example has besides.
 */
internal class SizeRows(examples: List<SizeExample>, val features: Int) {
    val count = examples.size
    val offsets = IntArray(count + 1)
    val ids: IntArray
    val y = DoubleArray(count) { examples[it].logRatio }
    val chars = DoubleArray(count) { examples[it].chars }

    init {
        examples.forEachIndexed { i, e -> offsets[i + 1] = offsets[i] + e.features.size }
        ids = IntArray(offsets[count])
        examples.forEachIndexed { i, e -> e.features.copyInto(ids, offsets[i]) }
    }

    /**
     * Of every feature, the weights of the examples that have it summed — of the mean, every example's — by [weights].
     */
    fun diagonal(weights: DoubleArray): DoubleArray = scattered { i -> weights[i] }

    /** Aᵀ W y: of every feature, the weighted logarithms of the examples that have it summed, by [weights]. */
    fun right(weights: DoubleArray): DoubleArray = scattered { i -> weights[i] * y[i] }

    /** Of every feature, [value] of every example that has it summed; of the mean, of every example: in parallel. */
    fun scattered(value: (Int) -> Double): DoubleArray {
        val parts =
            IntStream.range(0, PARTS)
                .parallel()
                .mapToObj { part ->
                    val out = DoubleArray(features)
                    for (i in part * count / PARTS until (part + 1) * count / PARTS) {
                        val v = value(i)
                        if (v == 0.0) continue
                        out[0] += v
                        for (k in offsets[i] until offsets[i + 1]) out[ids[k]] += v
                    }
                    out
                }
                .toList()
        return DoubleArray(features) { f -> parts.sumOf { it[f] } }
    }

    /** The example [i]'s product with [x]: the mean's and its every feature's. */
    fun dot(i: Int, x: DoubleArray): Double {
        var sum = x[0]
        for (k in offsets[i] until offsets[i + 1]) sum += x[ids[k]]
        return sum
    }

    companion object {
        /** The parts the examples are summed in, side by side. */
        val PARTS = Runtime.getRuntime().availableProcessors()
    }
}
