package io.github.vlsergey.recommend4me.piece

import java.util.stream.IntStream
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * THE LEADING DIRECTIONS OF VECTORS: of their second moments, not centred — so a cosine of two vectors read along them
 * stays near their cosine —, the [k] of the most by power iteration, every one after the ones before taken out of it.
 */
object PieceDirections {
    /** The rounds of the power iteration of a direction. */
    private const val ROUNDS = 60

    /**
     * The share of the sum of the second moments below which what is left is the rounding: the directions found are
     * kept in single precision.
     */
    private const val LEFT = 1e-6

    fun leading(vectors: List<FloatArray>, k: Int): List<FloatArray> {
        val d = vectors.first().size
        // The second moments, of the upper half, every row by a thread
        val c = Array(d) { DoubleArray(d) }
        IntStream.range(0, d).parallel().forEach { i ->
            val row = c[i]
            for (v in vectors) {
                val vi = v[i].toDouble()
                if (vi == 0.0) continue
                for (j in i until d) row[j] += vi * v[j]
            }
        }
        for (i in 0 until d) for (j in 0 until i) c[i][j] = c[j][i]
        // What is left below this share of the whole, after the directions found, is nought: the rounding of the sums
        val least = (0 until d).sumOf { c[it][it] } * LEFT
        val out = ArrayList<FloatArray>()
        val draw = Random(1)
        repeat(k) {
            var v = DoubleArray(d) { draw.nextDouble() - 0.5 }
            repeat(ROUNDS) {
                val prev = v
                val w =
                    IntStream.range(0, d)
                        .parallel()
                        .mapToDouble { i ->
                            var s = 0.0
                            val row = c[i]
                            for (j in 0 until d) s += row[j] * prev[j]
                            s
                        }
                        .toArray()
                for (f in out) {
                    var p = 0.0
                    for (i in 0 until d) p += w[i] * f[i]
                    for (i in 0 until d) w[i] -= p * f[i]
                }
                // Of vectors of fewer directions than asked, what is left after those found is nought: so is the rest
                val n = sqrt(w.sumOf { it * it })
                v = if (n > least) DoubleArray(d) { w[it] / n } else DoubleArray(d)
            }
            out += FloatArray(d) { v[it].toFloat() }
        }
        return out
    }

    fun unit(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return if (n > 0) FloatArray(v.size) { v[it] / n } else v
    }
}
