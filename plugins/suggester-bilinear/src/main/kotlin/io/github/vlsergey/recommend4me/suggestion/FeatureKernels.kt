package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels

/**
 * The kernels of the items' features on the card (`features.cu`): a block's rows carried onto the items' rows and
 * columns and taken from them, the ones summed, and what a row is shown with chosen. Compiled once for the card of the
 * process: the code, nothing of the data.
 */
internal object FeatureKernels {
    val NAMES =
        listOf(
            "halves_to_floats",
            "scatter_columns",
            "gather_columns",
            "scatter_rows",
            "ones_rows",
            "ones_coordinates",
            "ones_gradient",
            "shown_one",
            "shown_pack",
            "shown_likely",
        )

    /** The coordinates of a value a kernel holds at most (`MAX_RANK` of `features.cu`). */
    const val MAX_RANK = 64

    /** The values besides the likely a kernel chooses at most (`MAX_LEAST`). */
    const val MAX_LEAST = 8

    /**
     * The likely values of a row the one pass keeps in its stripe (`CAP` of `features.cu`): a row of more is written
     * again whole.
     */
    const val STRIPE = 32

    /** The rows of a block made single precision at a time on the card, for a product to read: memory, not meaning. */
    const val CARD_ROWS = 16_384

    /** The rows [from] to [from] + [count] of [rows], kept in half precision, written into [into] as single. */
    fun floats(kernels: CudaKernels, rows: CardRows, from: Int, count: Int, into: Cublas.DeviceFloats) {
        val n = count.toLong() * rows.dim
        kernels.launch("halves_to_floats", n, n, rows.vectors.at(from.toLong() * rows.dim * 2), into)
    }

    private var compiledFor: Cublas? = null
    private var compiled: CudaKernels? = null

    /** The kernels for the card of [cublas]; null when they cannot be made — that work stays on the processor. */
    @Synchronized
    fun of(cublas: Cublas): CudaKernels? {
        if (compiledFor !== cublas) {
            compiledFor = cublas
            compiled = cublas.kernels(FeatureKernels::class.java.getResource("features.cu")!!.readText(), NAMES)
        }
        return compiled
    }
}
