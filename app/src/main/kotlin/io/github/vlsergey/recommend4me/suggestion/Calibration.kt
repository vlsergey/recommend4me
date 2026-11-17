package io.github.vlsergey.recommend4me.suggestion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.exp
import kotlin.math.ln

/** One answer of the user on a value of a work, with the score a model that had not seen it gave the value. */
class Answered(val value: String, val score: Float, val yes: Boolean)

/**
 * THE CHANCES OF A FACET'S VALUES MADE TRUE TO THE USER'S ANSWERS (user, 2026-11-17), whatever suggester scored them:
 * of a score, the share of the values so scored the user says the work has.
 * - THE FACET'S CURVE: of every answer of the facet, the score against yes or no, made monotone (the pooling of the
 *   adjacent violators), each stretch's chance its yeses among its answers with half a yes and half a no besides — no
 *   chance of none or all made of a few answers ([scores], [chances]: the stretches' mean scores and their chances,
 *   ascending; between two the line, beyond the ends the end).
 * - EVERY VALUE'S OWN ([own]): its answers' slope and shift over the facet's curve on the scale of the log-odds, drawn
 *   to none — the facet's curve — by as much as the values are seen to differ from it, learnt of all of them (the
 *   empirical Bayes): a value of no answer the facet's curve, of a few between them, of many its own.
 * - THE CUTOFF ([threshold]): a value is likely of a chance at or above it — rather shown wrongly than hidden (user,
 *   2026-11-17): of every work's values not given by the site nor the user, the highest at which the values expected
 *   wrongly shown — the sum of one less the chance of those at or above it — are not fewer than those expected wrongly
 *   hidden — the sum of the chances of those under it ([balanced]); worked out of every pass of the facet's model over
 *   every work, one half till the first. Not of the answers' words: the user answers the values offered, the doubtful,
 *   mostly no — a cutoff of them hides nearly all.
 */
class Calibration(
    private val scores: FloatArray,
    private val chances: FloatArray,
    private val own: Map<String, Shift>,
    val threshold: Double,
    /** How many answers it was learnt of. */
    val answers: Int,
) {
    /** A value's slope and shift on the scale of the log-odds over the facet's curve. */
    class Shift(val slope: Double, val shift: Double)

    /** The chance of the value [value] scored [score]. */
    fun chance(value: String, score: Float): Double {
        val base = curve(score)
        val s = own[value] ?: return base
        return sigmoid(s.slope * logit(base) + s.shift)
    }

    /** Whether the value [value] scored [score] is likely: its chance at the [threshold] or above. */
    fun likely(value: String, score: Float): Boolean = chance(value, score) >= threshold

    /**
     * The score above which the value [value] is likely, for a suggester's choosing
     * ([io.github.vlsergey.recommend4me.suggestion.SuggestionScores.shown]): the lowest of the facet's curve's chance
     * the value needs, -∞ when every score is likely, +∞ when none is.
     */
    fun cutoff(value: String): Float {
        val s = own[value]
        val needed = if (s == null) threshold else sigmoid((logit(threshold) - s.shift) / s.slope)
        return when {
            chances.isEmpty() -> Float.NEGATIVE_INFINITY
            needed <= chances.first() -> Float.NEGATIVE_INFINITY
            needed > chances.last() -> Float.POSITIVE_INFINITY
            else -> {
                val k = (1 until chances.size).first { chances[it] >= needed }
                val (c0, c1) = chances[k - 1] to chances[k]
                val (s0, s1) = scores[k - 1] to scores[k]
                // Just under the score of the chance needed: a score above it is likely
                (if (c1 == c0) s1 else s0 + (s1 - s0) * ((needed - c0) / (c1 - c0)).toFloat()).let { Math.nextDown(it) }
            }
        }
    }

    /** The facet's curve of the [score]. */
    private fun curve(score: Float): Double =
        when {
            scores.isEmpty() -> NONE_LEARNT
            score <= scores.first() -> chances.first().toDouble()
            score >= scores.last() -> chances.last().toDouble()
            else -> {
                val k = (1 until scores.size).first { scores[it] >= score }
                val (s0, s1) = scores[k - 1] to scores[k]
                val (c0, c1) = chances[k - 1] to chances[k]
                if (s1 == s0) c1.toDouble() else c0 + (c1 - c0) * ((score - s0) / (s1 - s0)).toDouble()
            }
        }

    /** The same with the cutoff [threshold]. */
    fun withThreshold(threshold: Double) = Calibration(scores, chances, own, threshold, answers)

    fun pack(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeInt(answers)
            out.writeDouble(threshold)
            out.writeInt(scores.size)
            scores.indices.forEach {
                out.writeFloat(scores[it])
                out.writeFloat(chances[it])
            }
            out.writeInt(own.size)
            own.forEach { (value, s) ->
                out.writeUTF(value)
                out.writeDouble(s.slope)
                out.writeDouble(s.shift)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        /**
         * 3 — every calibration of 1 or 2 read as none: of the answers it was learnt of most were no word of the user's
         * (aff's universes 44 912 of 439 works, every universe a no of a work said to have none), the cutoffs at the
         * very top or bottom.
         */
        private const val VERSION = 3

        /** The chance of a facet learnt of no answer: one half, as a suggester's own. */
        private const val NONE_LEARNT = 0.5

        /** A calibration of [pack]; null of bytes of another version. */
        fun unpack(bytes: ByteArray): Calibration? =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != VERSION) return null
                val answers = input.readInt()
                val threshold = input.readDouble()
                val n = input.readInt()
                val scores = FloatArray(n)
                val chances = FloatArray(n)
                repeat(n) {
                    scores[it] = input.readFloat()
                    chances[it] = input.readFloat()
                }
                val own = HashMap<String, Shift>()
                repeat(input.readInt()) { own[input.readUTF()] = Shift(input.readDouble(), input.readDouble()) }
                Calibration(scores, chances, own, threshold, answers)
            }

        /**
         * Learnt of the [answered]: null of none, or of answers all of one word — of yeses alone every score is as
         * likely, the curve flat, and every value would be given: what tells a likely value from an unlikely one is
         * learnt of both.
         */
        fun fit(answered: List<Answered>, threshold: Double = NONE_LEARNT): Calibration? {
            if (answered.none { it.yes } || answered.all { it.yes }) return null
            val (scores, chances) = FacetCurve.of(answered)
            val facet = Calibration(scores, chances, emptyMap(), NONE_LEARNT, answered.size)
            val own = ValueShifts.of(answered) { a -> logit(facet.curve(a.score)) }
            return Calibration(scores, chances, own, threshold, answered.size)
        }

        /**
         * The highest of the [chances] at which those at or above it expected wrongly shown — the sum of one less each
         * — are not fewer than those under it expected wrongly hidden — the sum of each: rather shown wrongly than
         * hidden. One half of none.
         */
        fun balanced(chances: DoubleArray): Double {
            val down = chances.sortedArrayDescending()
            var hidden = down.sum()
            var shown = 0.0
            var k = 0
            var found: Double? = null
            while (k < down.size && found == null) {
                val at = down[k]
                // Every value of the same chance on the same side of it
                while (k < down.size && down[k] == at) {
                    hidden -= down[k]
                    shown += 1 - down[k]
                    k++
                }
                if (shown >= hidden) found = at
            }
            return found ?: down.lastOrNull() ?: NONE_LEARNT
        }

        fun logit(p: Double): Double = ln(p / (1 - p))

        fun sigmoid(x: Double): Double = 1 / (1 + exp(-x))
    }
}
