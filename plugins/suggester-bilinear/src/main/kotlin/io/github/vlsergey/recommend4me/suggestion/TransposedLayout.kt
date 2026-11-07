package io.github.vlsergey.recommend4me.suggestion

/**
 * A MODEL'S WEIGHTS AS AN ENSEMBLE LAYS THEM ([Ensemble]), against [Bilinear]'s layout: A (dx × rank), B (dz × rank)
 * and E (m × rank) each transposed — of a model its rank's rows of the item's features, of the value's, of the values —
 * the biases and the pair's own weights as they are.
 */
internal class TransposedLayout(private val dx: Int, private val dz: Int, private val m: Int, private val r: Int) {
    /** The numbers of one model, in the order its search reads them: Aᵀ, Bᵀ, Eᵀ, the biases, the pair's own weights. */
    val size = r * dx + r * dz + r * m + m + Bilinear.PAIR

    /**
     * Weights of [Bilinear]'s layout — A (dx × rank), B (dz × rank), E (m × rank), biases, the pair's — in this one's:
     * each transposed.
     */
    fun ordered(theta: FloatArray): FloatArray {
        val out = FloatArray(size)
        transpose(theta, 0, dx, out, 0)
        transpose(theta, dx * r, dz, out, r * dx)
        transpose(theta, dx * r + dz * r, m, out, r * dx + r * dz)
        theta.copyInto(out, r * dx + r * dz + r * m, dx * r + dz * r + m * r, theta.size)
        return out
    }

    /** This one's layout back in [Bilinear]'s. */
    fun standard(own: FloatArray): FloatArray {
        val out = FloatArray(size)
        untranspose(own, 0, dx, out, 0)
        untranspose(own, r * dx, dz, out, dx * r)
        untranspose(own, r * dx + r * dz, m, out, dx * r + dz * r)
        own.copyInto(out, dx * r + dz * r + m * r, r * dx + r * dz + r * m, own.size)
        return out
    }

    /** rows × rank at [from] of [src] written rank × rows at [to] of [out]. */
    private fun transpose(src: FloatArray, from: Int, rows: Int, out: FloatArray, to: Int) {
        for (f in 0 until rows) for (q in 0 until r) out[to + q * rows + f] = src[from + f * r + q]
    }

    /** rank × rows at [from] of [src] written rows × rank at [to] of [out]: [transpose] undone. */
    private fun untranspose(src: FloatArray, from: Int, rows: Int, out: FloatArray, to: Int) {
        for (f in 0 until rows) for (q in 0 until r) out[to + f * r + q] = src[from + q * rows + f]
    }
}
