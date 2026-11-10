package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.sigmoid
import kotlin.math.abs

/**
 * THE CHANCE A PAIR IS OF A KIND BY ITS SCORE, as the user's answers tell it: of the score of the model of the layers
 * (its logit [z]) the chance sigmoid([shift] + [slope] · z). The model learns of the pairs not answered too — nearly
 * every pair a search keeps, taken as not of the kind though some are —, so its own sigmoid of the score is far below
 * what the user answers of pairs scored so: a pair it gave 5 % the user said one work 9 times of 10. Fitted on the
 * pairs answered alone ([fit]), each by its score of a model that did not learn of it, the pairs answered being those
 * the user looked at: the chance is of a pair like those. Monotone, it orders the pairs as the score does.
 */
internal class Calibration(val shift: Double, val slope: Double) {

    fun chance(z: Double): Double = sigmoid(shift + slope * z)

    companion object {
        /** The chance as the model's own sigmoid of the score: of a model kept before the calibration was. */
        val NONE = Calibration(0.0, 1.0)

        private const val STEPS = 50

        /** A step small enough for the fitting to end. */
        private const val MOVED = 1e-9

        /**
         * How far [shift] and [slope] are held to [NONE]: a few answers, all of a kind, do not swing them to infinity.
         */
        private const val PENALTY = 1.0

        /**
         * The calibration of the scores [z] by the user's word on each ([yes]): Newton's steps of the log-likelihood of
         * a logistic model of one feature, held to [NONE] by [PENALTY].
         */
        fun fit(z: DoubleArray, yes: BooleanArray): Calibration {
            require(z.size == yes.size) { "${yes.size} words on ${z.size} scores" }
            var shift = 0.0
            var slope = 1.0
            var steps = 0
            var moved = Double.POSITIVE_INFINITY
            while (steps++ < STEPS && moved >= MOVED) {
                // The gradient and the Hessian of the penalized loss by (shift, slope)
                var g0 = PENALTY * shift
                var g1 = PENALTY * (slope - 1.0)
                var h00 = PENALTY
                var h01 = 0.0
                var h11 = PENALTY
                for (i in z.indices) {
                    val p = sigmoid(shift + slope * z[i])
                    val g = p - if (yes[i]) 1.0 else 0.0
                    val r = p * (1 - p)
                    g0 += g
                    g1 += g * z[i]
                    h00 += r
                    h01 += r * z[i]
                    h11 += r * z[i] * z[i]
                }
                val det = h00 * h11 - h01 * h01
                val d0 = (h11 * g0 - h01 * g1) / det
                val d1 = (h00 * g1 - h01 * g0) / det
                shift -= d0
                slope -= d1
                moved = maxOf(abs(d0), abs(d1))
            }
            return Calibration(shift, slope)
        }
    }
}
