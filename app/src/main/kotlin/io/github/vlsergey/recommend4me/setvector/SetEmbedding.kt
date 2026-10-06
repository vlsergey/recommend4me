package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A set of vectors of any size — a work's screenshots, its reviews, the windows of its text — as one vector of fixed length, without averaging away the single unusual one: a
 * sliced-Wasserstein embedding (Naderializadeh et al., "Pooling by Sliced-Wasserstein
 * Embedding", NeurIPS 2021). The set is a sample of a distribution; it is projected on a few
 * directions, and along each the quantiles of the projections are taken — the 5th percentile,
 * the quartiles, the 95th. A few pictures or lines unlike the rest move an outer quantile of
 * some direction instead of a hundredth of a mean; one vector and thirty give the same length.
 *
 * THE DIRECTIONS ARE NOT LEARNT FROM THE RATINGS, of which there are a hundred and fifty, but
 * from the whole catalogue: they are its principal components ([fit] on a sample of all
 * instances), and a projection is counted in standard deviations of the catalogue along it. The
 * ranking line then weighs the quantiles like any other feature.
 */
class SetEmbedding(val center: FloatArray, val directions: List<FloatArray>, val scales: FloatArray) {

    val dim: Int get() = directions.size * QUANTILES.size

    /** The embedding of the set; null for an empty one. */
    fun of(instances: List<FloatArray>): FloatArray? = quantiles(project(instances), null)

    /** The projections of every instance on every direction, in standard deviations: [direction][instance]. */
    fun project(instances: List<FloatArray>): Array<DoubleArray> = Array(directions.size) { s ->
        val direction = directions[s]
        DoubleArray(instances.size) { i ->
            val v = instances[i]
            var p = 0.0
            for (k in v.indices) p += (v[k] - center[k]) * direction[k]
            p / scales[s]
        }
    }

    /**
     * The embedding of the instances whose [keep] is true (all when null) from their
     * [projections] — so a set without one of its members costs sorting, not projecting again.
     */
    fun quantiles(projections: Array<DoubleArray>, keep: BooleanArray?): FloatArray? {
        val n = keep?.count { it } ?: projections.firstOrNull()?.size ?: 0
        if (n == 0) return null
        val out = FloatArray(dim)
        val sorted = DoubleArray(n)
        projections.forEachIndexed { s, all ->
            var j = 0
            for (i in all.indices) if (keep == null || keep[i]) sorted[j++] = all[i]
            sorted.sort()
            QUANTILES.forEachIndexed { q, level ->
                val at = level * (n - 1)
                val lo = at.toInt()
                val hi = minOf(lo + 1, n - 1)
                out[s * QUANTILES.size + q] = (sorted[lo] + (at - lo) * (sorted[hi] - sorted[lo])).toFloat()
            }
        }
        return out
    }

    /** The centre, the directions and the scales as float32 little-endian, after the dimension and the count. */
    fun pack(): ByteArray {
        val d = center.size
        val buffer = java.nio.ByteBuffer.allocate(8 + 4 * (d + directions.size * d + scales.size)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(d).putInt(directions.size)
        center.forEach { buffer.putFloat(it) }
        directions.forEach { v -> v.forEach { buffer.putFloat(it) } }
        scales.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    companion object {
        /**
         * Not the lowest and the highest: they run further out the bigger the set — of five
         * thousand phrases of reviews than of fifty — and would tell how many reviews a work has.
         * The 5th and the 95th percentiles stay where they are as the set grows.
         */
        val QUANTILES = doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)

        /** Directions of every embedding, and the length of what it gives. */
        const val DIRECTIONS = 32
        val DIM = DIRECTIONS * QUANTILES.size

        fun unpack(bytes: ByteArray): SetEmbedding {
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val d = buffer.int
            val count = buffer.int
            val center = FloatArray(d) { buffer.float }
            val directions = List(count) { FloatArray(d) { buffer.float } }
            val scales = FloatArray(count) { buffer.float }
            return SetEmbedding(center, directions, scales)
        }

        /**
         * The first [count] principal components of [sample] by orthogonal iteration on its
         * covariance — a d × d product by OpenBLAS, then [ITERATIONS] products by the components.
         */
        fun fit(sample: List<FloatArray>, count: Int): SetEmbedding {
            val d = sample.first().size
            val n = sample.size
            val center = FloatArray(d)
            sample.forEach { v -> for (k in 0 until d) center[k] += v[k] }
            for (k in 0 until d) center[k] /= n
            val x = Floats(n * d)
            sample.forEachIndexed { i, v -> for (k in 0 until d) x[i * d + k] = v[k] - center[k] }
            val covariance = Floats(d * d)
            Blas.transposedProduct(x, d, n, d, x, d, covariance)

            // Orthogonal iteration: Q ← orthonormalised(C·Q)
            val random = Random(0)
            var q = Array(count) { DoubleArray(d) { random.nextDouble() - 0.5 } }
            orthonormalise(q)
            val qs = Floats(d * count)
            val cq = Floats(d * count)
            repeat(ITERATIONS) {
                for (s in 0 until count) for (k in 0 until d) qs[k * count + s] = q[s][k].toFloat()
                Blas.product(covariance, d, d, d, qs, count, cq)
                q = Array(count) { s -> DoubleArray(d) { k -> cq[k * count + s].toDouble() } }
                orthonormalise(q)
            }
            // The spread along each: √(qᵀCq / n)
            for (s in 0 until count) for (k in 0 until d) qs[k * count + s] = q[s][k].toFloat()
            Blas.product(covariance, d, d, d, qs, count, cq)
            val scales = FloatArray(count) { s ->
                var r = 0.0
                for (k in 0 until d) r += q[s][k] * cq[k * count + s]
                sqrt(max(r / n, 1e-12)).toFloat()
            }
            return SetEmbedding(center, q.map { v -> FloatArray(d) { v[it].toFloat() } }, scales)
        }

        /** Gram–Schmidt, in place, in order: the first vector keeps its direction. */
        private fun orthonormalise(vs: Array<DoubleArray>) {
            for (i in vs.indices) {
                for (j in 0 until i) {
                    var dot = 0.0
                    for (k in vs[i].indices) dot += vs[i][k] * vs[j][k]
                    for (k in vs[i].indices) vs[i][k] -= dot * vs[j][k]
                }
                var norm = 0.0
                for (x in vs[i]) norm += x * x
                norm = sqrt(max(norm, 1e-24))
                for (k in vs[i].indices) vs[i][k] /= norm
            }
        }

        private const val ITERATIONS = 40
    }
}
