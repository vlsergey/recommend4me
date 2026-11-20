package io.github.vlsergey.recommend4me.size

import kotlin.math.abs
import kotlin.math.max
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(SizeVariances::class.java)

/**
 * THE PENALTIES AND THE WEIGHTS OF THE WORDS TO A CHARACTER WORKED OUT OF THE DATA (owner, 2026-11-20: no number of the
 * code's own), as the variances of a model of them ([Variances]): the logarithm of a work's ratio is the mean and the
 * steps of its features, each step of a kind spread by the kind's variance (a penalty of its inverse), and a deviation
 * of the work's own, of two parts — the work's itself, whatever its length, and its count's, the fewer characters the
 * more: an example weighs the inverse of their sum. Worked out in turns till they stand: the kinds' variances chosen by
 * the error on the examples held out ([SizePenalties]), the steps solved of them, and the two parts of the deviation of
 * the residuals, each of its leverage taken back, by its characters ([lengths]). [kinds]: of every feature its kind, of
 * the mean none.
 */
internal class SizeVariances(private val rows: SizeRows, private val kinds: IntArray, private val ridge: SizeRidge) {
    private val kindCount = WordsPerChar.KINDS.size

    /** The solutions the choices of the penalties took, and the rounds of the choice: what a fitting cost. */
    var choosing = 0
        private set

    var rounds = 0
        private set

    /**
     * The steps [x] fitted of the penalties [lambda], the [variances] they are of, how much each example is trusted
     * ([trust]: of the examples of two sites, the chance they are of one text; none — every one wholly), and the error
     * of the model on the tenth of the examples held out of its choice ([held]).
     */
    class Fitted(
        val x: DoubleArray,
        val lambda: DoubleArray,
        val variances: Variances,
        private val trust: DoubleArray?,
        private val chars: DoubleArray,
        val held: Double,
    ) {
        /** The weight of every example: its trust over the variance of its deviation. */
        fun weights(): DoubleArray = variances.weights(chars, trust)
    }

    /**
     * The model fitted, begun at the variances [from] — those the last fitting stood at, kept with its model: of data
     * that changed little, one turn (2026-11-20: begun anew every time, a fitting took ten minutes).
     */
    fun fit(from: Variances? = null): Fitted {
        val spread = variance(rows.y)
        // Examples of one ratio — one of them, say —: nothing varies to tell a variance of, the model is their mean
        if (spread <= 0)
            return Fitted(
                DoubleArray(rows.features).also { if (rows.count > 0) it[0] = rows.y.average() },
                DoubleArray(rows.features),
                Variances(0.0, 0.0, DoubleArray(kindCount)),
                null,
                rows.chars,
                0.0,
            )
        var at = from ?: Variances(spread, 0.0, DoubleArray(kindCount) { spread })
        var x: DoubleArray? = null
        var held = Double.MAX_VALUE
        val choice = SizePenalties(rows, kinds, kindCount, ridge)
        // In turns: the deviation's two parts of the penalties standing, then the penalties chosen of them — till a
        // choice lowers the error held out by less than [CHANGE] of it, or moves nothing (2026-11-20: a choice of
        // steps by factors flipped between two, and every one of 200 turns chose them anew, 3618 solutions)
        for (round in 1..MOST_ROUNDS) {
            val deviation = deviation(at, x)
            at = deviation.first
            x = deviation.second
            val before = ridge.solutions
            val chosen = choice.chosen(at.weights(rows.chars, null), at.tau, x)
            choosing += ridge.solutions - before
            rounds++
            val better = chosen.error < held * (1 - CHANGE)
            val moved = !chosen.tau.contentEquals(at.tau)
            held = chosen.error
            at = Variances(at.noise, at.counting, chosen.tau)
            if (!better || !moved) break
        }
        x = ridge.solve(at.weights(rows.chars, null), penalties(at.tau), x)
        return Fitted(x, penalties(at.tau), at, null, rows.chars, held)
    }

    /**
     * The two parts of the deviation worked out of the residuals of the penalties of [at] standing, in turns till they
     * move by less than [CHANGE]: one solution a turn, the steps begun at [from]; and the steps last solved.
     */
    private fun deviation(at: Variances, from: DoubleArray?): Pair<Variances, DoubleArray> {
        val lambda = penalties(at.tau)
        var now = at
        var x = from
        val turns = ArrayDeque<String>()
        for (turn in 1..MOST_ROUNDS) {
            val weights = now.weights(rows.chars, null)
            val began = ridge.steps
            val solved = ridge.solve(weights, lambda, x)
            x = solved
            val (noise, counting) = lengths(solved, rows.diagonal(weights), lambda, now)
            val moved = maxOf(changed(now.noise, noise), changed(now.counting, counting))
            now = Variances(noise, counting, at.tau)
            if (moved < CHANGE) return now to solved
            turns.addLast("%.6g %.6g (%d steps)".format(noise, counting, ridge.steps - began))
            if (turns.size > TOLD) turns.removeFirst()
        }
        log.info("the deviation of the words to a character did not settle; its last turns: {}", turns.joinToString())
        return now to x!!
    }

    /** Of every feature, its kind's penalty: the inverse of the kind's variance; the mean held by none. */
    private fun penalties(tau: DoubleArray) =
        DoubleArray(rows.features) { f -> if (kinds[f] < 0) 0.0 else 1 / tau[kinds[f]] }

    /**
     * The two parts of the examples' deviation: every residual's square by the inverse of its characters — a weighted
     * least squares of a line, weighing each by the inverse of its variance's square, of the variances [at] —, the
     * squares made larger by the share of the examples the steps fitted ([freedom]); a part below nought, nought, the
     * other of the rest. Of all the examples, not of each: an example's own leverage, told of the features' diagonal
     * alone, came to one and beyond for an example of several rare features, its square divided by nought — the
     * deviation went from 0.003 to 1565 and back every other turn (2026-11-20).
     */
    private fun lengths(
        x: DoubleArray,
        diagonal: DoubleArray,
        lambda: DoubleArray,
        at: Variances,
    ): Pair<Double, Double> {
        val fitted = freedom(diagonal, lambda)
        val kept = if (fitted < rows.count) (rows.count - fitted) / rows.count else 1.0
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var t0 = 0.0
        var t1 = 0.0
        for (i in 0 until rows.count) {
            val residual = rows.y[i] - rows.dot(i, x)
            val variance = residual * residual / kept
            val v = 1 / rows.chars[i]
            val expected = at.variance(rows.chars[i])
            val omega = 1 / (expected * expected)
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
            nextNoise = max(Double.MIN_VALUE, at.noise * Double.MIN_VALUE)
        }
        return nextNoise to nextCounting
    }

    /**
     * The number of the steps the data fitted, of every feature its examples' weight in the [diagonal] over that and
     * its penalty [lambda] (the trace of the matrix of influence, of its diagonal alone): the mean's one, a step held
     * by its penalty less.
     */
    private fun freedom(diagonal: DoubleArray, lambda: DoubleArray): Double {
        var sum = 0.0
        for (f in diagonal.indices) if (diagonal[f] + lambda[f] > 0) sum += diagonal[f] / (diagonal[f] + lambda[f])
        return sum
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

        /** The last turns of a deviation that did not settle told in the log. */
        const val TOLD = 6
    }
}

/**
 * THE VARIANCES A MODEL OF THE WORDS TO A CHARACTER IS OF: a work's own deviation ([noise]), its count's by a character
 * ([counting]), the spread of the steps of every kind ([tau], by [WordsPerChar.KINDS]) — kept with the model by their
 * names ([named], [kept]), the next fitting begun at them.
 */
internal class Variances(val noise: Double, val counting: Double, val tau: DoubleArray) {
    /**
     * The characters a work's own count weighs as much as the model by: its count as uncertain as its own deviation; of
     * no deviation told at all, none — its own count is the model.
     */
    val sample: Double
        get() = if (noise > 0) counting / noise else 0.0

    /** The variance of the deviation of a text of [chars] characters from the model. */
    fun variance(chars: Double) = noise + counting / chars

    /** The weight of every example of the [chars]: its [trust] — of none, whole — over its variance. */
    fun weights(chars: DoubleArray, trust: DoubleArray?): DoubleArray =
        DoubleArray(chars.size) { (trust?.get(it) ?: 1.0) / variance(chars[it]) }

    /** The variances by the names they are kept by among the model's weights: no feature is named so. */
    fun named(): Map<String, Double> =
        mapOf(NOISE to noise, COUNTING to counting) +
            WordsPerChar.KINDS.indices.associate { TAU + WordsPerChar.KINDS[it] to tau[it] }

    companion object {
        /** The beginning of the names of the variances among the weights kept: no feature's. */
        private const val PREFIX = "model."
        private const val NOISE = PREFIX + "noise"
        private const val COUNTING = PREFIX + "counting"
        private const val TAU = PREFIX + "tau:"

        /**
         * The variances of the kept [weights] by their names; none of a model kept without them, or of one of examples
         * that varied in nothing — no variance told, nought, nothing to begin at (2026-11-21: a model of one example
         * kept, the next fitting weighed every example by the inverse of nought).
         */
        fun kept(weights: Map<String, Double>): Variances? {
            val tau = WordsPerChar.KINDS.map { weights[TAU + it] }
            val noise = weights[NOISE]
            val counting = weights[COUNTING]
            if (noise == null || counting == null || tau.any { it == null }) return null
            if (noise <= 0 || tau.any { it!! <= 0 }) return null
            return Variances(noise, counting, DoubleArray(tau.size) { tau[it]!! })
        }
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
