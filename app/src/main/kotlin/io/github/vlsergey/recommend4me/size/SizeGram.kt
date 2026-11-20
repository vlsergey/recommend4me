package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.matrix.CardMemory
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import java.util.stream.IntStream

/**
 * The examples of the words to a character as rows of features, flat: every example's features one after another
 * ([features], the [offsets] of each example's first and of the end), its logarithm of the ratio ([y]) and its weight;
 * of [features] numbers the first of which, the mean, every example has besides.
 */
internal class SizeRows(examples: List<SizeExample>, val features: Int) {
    val count = examples.size
    val offsets = IntArray(count + 1)
    val ids: IntArray
    val y = DoubleArray(count) { examples[it].logRatio }
    val weight = DoubleArray(count) { examples[it].weight }

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

/**
 * THE PRODUCT OF THE WEIGHED RIDGE'S MATRIX Aᵀ W A WITH A VECTOR — the penalty is the caller's —, of the examples' rows
 * [rows] by the weights last [weigh]ed: on the processor or the card.
 */
internal interface SizeGram : AutoCloseable {
    fun weigh(weights: DoubleArray)

    fun times(x: DoubleArray): DoubleArray

    override fun close() {}

    /** On the processor, the examples in parts side by side. */
    class OnProcessor(private val rows: SizeRows) : SizeGram {
        private var weights = rows.weight

        override fun weigh(weights: DoubleArray) {
            this.weights = weights
        }

        override fun times(x: DoubleArray): DoubleArray = rows.scattered { i -> weights[i] * rows.dot(i, x) }
    }

    /**
     * On the card: the rows, the weights and the products of the examples kept in the card's [memory], the vector sent
     * and the product brought back at every step — two kernels of their own ([KERNELS]).
     */
    class OnCard(private val rows: SizeRows, private val kernels: CudaKernels, memory: CardMemory) : SizeGram {
        private val offsets = memory.bytes(rows.offsets.size.toLong() * Int.SIZE_BYTES).also { it.upload(rows.offsets) }
        private val ids = memory.bytes(maxOf(1, rows.ids.size).toLong() * Int.SIZE_BYTES).also { it.upload(rows.ids) }
        private val weights = memory.bytes(rows.count.toLong() * Double.SIZE_BYTES)
        private val products = memory.bytes(rows.count.toLong() * Double.SIZE_BYTES)
        private val x = memory.bytes(rows.features.toLong() * Double.SIZE_BYTES)
        private val out = memory.bytes(rows.features.toLong() * Double.SIZE_BYTES)

        init {
            weigh(rows.weight)
        }

        override fun weigh(weights: DoubleArray) = this.weights.upload(bits(weights))

        override fun times(x: DoubleArray): DoubleArray {
            this.x.upload(bits(x))
            out.zero()
            val n = rows.count.toLong()
            kernels.launch("size_rows", n, rows.count, offsets, ids, weights, this.x, products)
            kernels.launch("size_scatter", n, rows.count, offsets, ids, products, out)
            return out.doubles(rows.features)
        }

        private fun bits(v: DoubleArray) = LongArray(v.size) { java.lang.Double.doubleToRawLongBits(v[it]) }

        companion object {
            val KERNELS = listOf("size_rows", "size_scatter")

            /** The bytes the card holds of [rows]: what a block of its memory is asked for. */
            fun bytes(rows: SizeRows): Long =
                (rows.offsets.size + rows.ids.size).toLong() * Int.SIZE_BYTES +
                    (2L * rows.count + 2L * rows.features) * Double.SIZE_BYTES

            /** The kernels of the card [cublas]; none when they would not compile. */
            fun kernels(cublas: Cublas): CudaKernels? =
                cublas.kernels(OnCard::class.java.getResource("size.cu")!!.readText(), KERNELS)
        }
    }
}
