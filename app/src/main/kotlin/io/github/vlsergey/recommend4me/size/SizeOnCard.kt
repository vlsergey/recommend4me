package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.matrix.CardMemory
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import kotlin.math.sqrt

/**
 * THE RIDGE OF THE WORDS TO A CHARACTER SOLVED WHOLLY ON THE CARD (owner, 2026-11-20: "move it all to the card"): the
 * examples' rows, their logarithms, weights and products, and every vector of the conjugate gradients — the solution,
 * the residual, the preconditioned one, the direction, the diagonal, the penalties — kept in the card's [memory]; a
 * solution sends the weights and the penalties and brings the solution back, a step one sum of its own ([summed]): no
 * vector of the features goes through the bus at a step (2026-11-20: sent and brought back at every one, with the
 * vectors' sums on the processor, 7.5 ms a step). Kernels of its own ([KERNELS], `size.cu`).
 */
internal class SizeOnCard(
    private val rows: SizeRows,
    private val kernels: CudaKernels,
    private val memory: CardMemory,
) : SizeSolver {
    private val n = rows.count
    private val m = rows.features
    private val offsets = memory.bytes((n + 1L) * Int.SIZE_BYTES).also { it.upload(rows.offsets) }
    private val ids = memory.bytes(maxOf(1, rows.ids.size).toLong() * Int.SIZE_BYTES).also { it.upload(rows.ids) }
    private val y = ofExamples().also { it.upload(bits(rows.y)) }
    private val weights = ofExamples()
    private val products = ofExamples()
    private val lambda = ofFeatures()
    private val diagonal = ofFeatures()
    private val b = ofFeatures()
    private val x = ofFeatures()
    private val r = ofFeatures()
    private val z = ofFeatures()
    private val p = ofFeatures()
    private val ap = ofFeatures()
    private val sum = memory.bytes(Double.SIZE_BYTES.toLong())

    private fun ofExamples() = memory.bytes(n.toLong() * Double.SIZE_BYTES)

    private fun ofFeatures() = memory.bytes(m.toLong() * Double.SIZE_BYTES)

    override fun solve(
        weights: DoubleArray,
        lambda: DoubleArray,
        from: DoubleArray?,
        tolerance: Double,
        most: Int,
    ): SizeSolver.Solved {
        this.weights.upload(bits(weights))
        this.lambda.upload(bits(lambda))
        // The diagonal: of every feature the weights of its examples summed, and its penalty
        diagonal.zero()
        scatter(this.weights, diagonal)
        features("size_add", this.lambda, diagonal)
        weigh(0.0)
        b.zero()
        scatter(products, b)
        val mean = b.doubles(1)[0] / diagonal.doubles(1)[0].coerceAtLeast(Double.MIN_VALUE)
        // The examples' spread about their mean, in the residual's room till the residual is made
        weigh(mean)
        r.zero()
        scatter(products, r)
        val enough = tolerance * sqrt(summed { features("size_scaled", r, diagonal, sum) })
        x.upload(bits(from ?: DoubleArray(m).also { it[0] = mean }))
        times(x, ap)
        var rz = summed { features("size_start", b, ap, this.lambda, x, diagonal, r, z, p, sum) }
        var taken = 0
        while (taken < most && sqrt(rz) > enough) {
            taken++
            times(p, ap)
            val alpha = rz / summed { features("size_curve", p, ap, this.lambda, sum) }
            val next = summed { features("size_step", alpha, p, ap, diagonal, x, r, z, sum) }
            features("size_direction", next / rz, z, p)
            rz = next
        }
        return SizeSolver.Solved(x.doubles(m), taken)
    }

    /** Aᵀ W A [v] into [out]: of every example its weight times its row's product, scattered over its features. */
    private fun times(v: Cublas.DeviceBytes, out: Cublas.DeviceBytes) {
        out.zero()
        kernels.launch("size_rows", n.toLong(), n, offsets, ids, weights, v, products)
        scatter(products, out)
    }

    /** Of every example its [values] added to every feature of its row, and to the mean's, in [out]. */
    private fun scatter(values: Cublas.DeviceBytes, out: Cublas.DeviceBytes) =
        kernels.launch("size_scatter", n.toLong(), n, offsets, ids, values, out)

    /** Of every example its weight times its logarithm less [shift], in the products. */
    private fun weigh(shift: Double) = kernels.launch("size_weigh", n.toLong(), n, weights, y, shift, products)

    /** The kernel [name] over the features, of [args] after their number. */
    private fun features(name: String, vararg args: Any) = kernels.launch(name, m.toLong(), m, *args)

    /** The sum the kernels of [body] add to: begun at nought, brought back. */
    private fun summed(body: () -> Unit): Double {
        sum.zero()
        body()
        return sum.doubles(1)[0]
    }

    private fun bits(v: DoubleArray) = LongArray(v.size) { java.lang.Double.doubleToRawLongBits(v[it]) }

    companion object {
        val KERNELS =
            listOf(
                "size_rows",
                "size_scatter",
                "size_weigh",
                "size_add",
                "size_scaled",
                "size_start",
                "size_curve",
                "size_step",
                "size_direction",
            )

        /** The vectors of the features kept on the card. */
        private const val FEATURE_VECTORS = 8

        /** The vectors of the examples kept on the card. */
        private const val EXAMPLE_VECTORS = 3

        /** The bytes the card holds of [rows]: what a block of its memory is asked for. */
        fun bytes(rows: SizeRows): Long =
            (rows.offsets.size + maxOf(1, rows.ids.size)).toLong() * Int.SIZE_BYTES +
                (EXAMPLE_VECTORS.toLong() * rows.count + FEATURE_VECTORS.toLong() * rows.features + 1) *
                    Double.SIZE_BYTES

        /** The kernels of the card [cublas]; none when they would not compile. */
        fun kernels(cublas: Cublas): CudaKernels? =
            cublas.kernels(SizeOnCard::class.java.getResource("size.cu")!!.readText(), KERNELS)
    }
}
