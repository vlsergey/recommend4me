package io.github.vlsergey.recommend4me.size

import kotlin.math.abs
import kotlin.math.max

/**
 * THE PENALTIES AND THE WEIGHTS OF THE WORDS TO A CHARACTER WORKED OUT OF THE DATA (owner, 2026-11-20: no number of the
 * code's own), as the variances of a model of them: the logarithm of a work's ratio is the mean and the steps of its
 * features, each step of a kind spread by the kind's variance ([Fitted.tau]: a penalty of 1/τ²), and a deviation of the
 * work's own, of two parts — the work's itself, whatever its length ([Fitted.noise]), and its count's, the fewer
 * characters the more ([Fitted.counting] / its characters): an example weighs the inverse of their sum. Worked out in
 * turns till they stand: the steps solved of the variances, the variances of the steps — a kind's of the sum of its
 * steps' squares over the number of them the data, not the penalty, tell (their effective number, MacKay's) — and of
 * the residuals, each of its leverage taken back, by its characters ([lengths]). [kinds]: of every feature its kind, of
 * the mean none.
 */
internal class SizeVariances(private val rows: SizeRows, private val kinds: IntArray, private val ridge: SizeRidge) {
    private val kindCount = (kinds.maxOrNull() ?: -1) + 1

    /**
     * The steps [x] fitted of the penalties [lambda], the variances they are of, and how much each example is trusted
     * ([trust]: of the examples of two sites, the chance they are of one text; none — every one wholly).
     */
    class Fitted(
        val x: DoubleArray,
        val lambda: DoubleArray,
        val noise: Double,
        val counting: Double,
        val tau: DoubleArray,
        private val trust: DoubleArray?,
        private val chars: DoubleArray,
    ) {
        /**
         * The characters a work's own count weighs as much as the model by: its count as uncertain as its own
         * deviation; of no deviation told at all, none — its own count is the model.
         */
        val sample: Double
            get() = if (noise > 0) counting / noise else 0.0

        /** The weight of every example: its trust over the variance of its deviation. */
        fun weights(): DoubleArray =
            DoubleArray(chars.size) { (trust?.get(it) ?: 1.0) / (noise + counting / chars[it]) }

        /** The variance of the deviation of a text of [chars] characters from the model. */
        fun variance(chars: Double) = noise + counting / chars
    }

    /** The model fitted, the examples trusted by [trust], begun at what [from] fitted. */
    fun fit(trust: DoubleArray? = null, from: Fitted? = null): Fitted {
        val spread = variance(rows.y)
        // Examples of one ratio — one of them, say —: nothing varies to tell a variance of, the model is their mean
        if (spread <= 0)
            return Fitted(
                DoubleArray(rows.features).also { if (rows.count > 0) it[0] = rows.y.average() },
                DoubleArray(rows.features),
                0.0,
                0.0,
                DoubleArray(kindCount),
                trust,
                rows.chars,
            )
        var noise = from?.noise ?: spread
        var counting = from?.counting ?: 0.0
        var tau = from?.tau?.copyOf() ?: DoubleArray(kindCount) { spread }
        var x = from?.x
        var lambda = penalties(tau)
        for (round in 1..MOST_ROUNDS) {
            val weights = Fitted(DoubleArray(0), lambda, noise, counting, tau, trust, rows.chars).weights()
            lambda = penalties(tau)
            x = ridge.solve(weights, lambda, x)
            val diagonal = rows.diagonal(weights)
            val nextTau = steps(x, diagonal, lambda, tau)
            val (nextNoise, nextCounting) = lengths(x, weights, diagonal, lambda, noise, counting, trust)
            val moved =
                maxOf(
                    changed(noise, nextNoise),
                    changed(counting, nextCounting),
                    tau.indices.maxOfOrNull {
                        changed(tau[it], nextTau[it])
                    } ?: 0.0,
                )
            noise = nextNoise
            counting = nextCounting
            tau = nextTau
            if (moved < CHANGE) break
        }
        lambda = penalties(tau)
        return Fitted(x!!, lambda, noise, counting, tau, trust, rows.chars)
    }

    /** Of every feature, its kind's penalty: the inverse of the kind's variance; the mean held by none. */
    private fun penalties(tau: DoubleArray) =
        DoubleArray(rows.features) { f -> if (kinds[f] < 0) 0.0 else 1 / tau[kinds[f]] }

    /**
     * Every kind's variance of its steps [x]: the sum of their squares over their effective number, a step's share the
     * data tell of it ([diagonal] over [diagonal] and its penalty [lambda]); a kind of none, as it was ([tau]).
     */
    private fun steps(x: DoubleArray, diagonal: DoubleArray, lambda: DoubleArray, tau: DoubleArray): DoubleArray {
        val squares = DoubleArray(kindCount)
        val effective = DoubleArray(kindCount)
        for (f in 1 until rows.features) {
            val k = kinds[f]
            if (k < 0) continue
            squares[k] += x[f] * x[f]
            effective[k] += diagonal[f] / (diagonal[f] + lambda[f])
        }
        return DoubleArray(kindCount) { k ->
            if (effective[k] > 0 && squares[k] > 0) squares[k] / effective[k] else tau[k]
        }
    }

    /**
     * The two parts of the examples' deviation: every residual's square, its leverage taken back, by the inverse of its
     * characters — a weighted least squares of a line, weighing each by the inverse of its variance's square; a part
     * below nought, nought, the other of the rest.
     */
    private fun lengths(
        x: DoubleArray,
        weights: DoubleArray,
        diagonal: DoubleArray,
        lambda: DoubleArray,
        noise: Double,
        counting: Double,
        trust: DoubleArray?,
    ): Pair<Double, Double> {
        val inverse =
            DoubleArray(rows.features) { if (diagonal[it] + lambda[it] > 0) 1 / (diagonal[it] + lambda[it]) else 0.0 }
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var t0 = 0.0
        var t1 = 0.0
        for (i in 0 until rows.count) {
            var leverage = inverse[0]
            for (k in rows.offsets[i] until rows.offsets[i + 1]) leverage += inverse[rows.ids[k]]
            val kept = 1 - minOf(weights[i] * leverage, ALMOST_ONE)
            val residual = rows.y[i] - rows.dot(i, x)
            val variance = residual * residual / kept
            val v = 1 / rows.chars[i]
            val expected = noise + counting * v
            val omega = (trust?.get(i) ?: 1.0) / (expected * expected)
            s0 += omega
            s1 += omega * v
            s2 += omega * v * v
            t0 += omega * variance
            t1 += omega * v * variance
        }
        val det = s0 * s2 - s1 * s1
        var nextNoise = if (det > 0) (s2 * t0 - s1 * t1) / det else t0 / s0
        var nextCounting = if (det > 0) (s0 * t1 - s1 * t0) / det else 0.0
        if (nextCounting < 0) {
            nextCounting = 0.0
            nextNoise = t0 / s0
        }
        if (nextNoise <= 0) {
            nextCounting = t1 / s2
            nextNoise = max(Double.MIN_VALUE, noise * Double.MIN_VALUE)
        }
        return nextNoise to nextCounting
    }

    private fun variance(v: DoubleArray): Double {
        val mean = v.average()
        return v.sumOf { (it - mean) * (it - mean) } / max(1, v.size - 1)
    }

    /** How much [b] stands off [a], of the larger of the two. */
    private fun changed(a: Double, b: Double): Double {
        val scale = max(abs(a), abs(b))
        return if (scale == 0.0) 0.0 else abs(a - b) / scale
    }

    private companion object {
        /**
         * How little the variances move between two turns when they stand: half a percent (owner, 2026-11-20: "no sense
         * doing mathematics here").
         */
        const val CHANGE = 0.005

        /** The turns at most: of variances that stand, never near. */
        const val MOST_ROUNDS = 200

        /** A leverage below one: a residual of a step of one example alone is not made infinite. */
        const val ALMOST_ONE = 1 - 1e-9
    }
}

/**
 * HOW MUCH THE EXAMPLES OF TWO SITES ARE TRUSTED — one site's words of a work and another's characters of it, which may
 * be of another version or of a part —: by a mixture of two of their residuals from the model of the rest ([of]), one
 * of the same text, of the variance the model tells of a text of their characters, the other of another, its mean and
 * spread worked out with the share of each; an example trusted as the chance it is of the first. No cut.
 */
internal object SizeTrust {
    /** The chance of every residual [residuals] of the variance [variances] of the same text that it is of it. */
    fun of(residuals: DoubleArray, variances: DoubleArray): DoubleArray {
        val n = residuals.size
        if (n == 0) return DoubleArray(0)
        var share = HALF
        var mean = residuals.average()
        var spread = residuals.sumOf { (it - mean) * (it - mean) } / n + variances.average()
        val same = DoubleArray(n)
        for (round in 1..MOST_ROUNDS) {
            for (i in 0 until n) {
                val a = share * density(residuals[i], 0.0, variances[i])
                val b = (1 - share) * density(residuals[i], mean, spread)
                same[i] = if (a + b > 0) a / (a + b) else 1.0
            }
            val other = n - same.sum()
            val next = same.average()
            if (other > 0) {
                mean = residuals.indices.sumOf { (1 - same[it]) * residuals[it] } / other
                spread =
                    max(
                        Double.MIN_VALUE,
                        residuals.indices.sumOf { (1 - same[it]) * (residuals[it] - mean).let { d -> d * d } } / other,
                    )
            }
            val moved = abs(next - share) / max(next, share)
            share = next
            if (moved < CHANGE) break
        }
        return same
    }

    private fun density(x: Double, mean: Double, variance: Double): Double =
        kotlin.math.exp(-(x - mean) * (x - mean) / (2 * variance)) / kotlin.math.sqrt(2 * Math.PI * variance)

    /** The share of the same text begun at: either as likely. */
    private const val HALF = 0.5

    /** How little the share of the same text moves between two turns when it stands: half a percent of it. */
    private const val CHANGE = 0.005
    private const val MOST_ROUNDS = 1000
}
