package io.github.vlsergey.recommend4me.suggestion

import kotlin.math.abs

/**
 * A VALUE'S OWN SLOPE AND SHIFT OVER THE FACET'S CURVE BY FIRTH'S PENALIZED LIKELIHOOD (Firth 1993; Heinze and Schemper
 * 2002), of its answers' log-odds by the curve `x` and their words, the log-odds `slope·x + shift`: the likelihood
 * times the root of the determinant of its information (Jeffreys' prior), which is finite where the most likely is not
 * — of answers all of one word, as most of a value's few are, the most likely shift is infinite. What the values'
 * spread is learnt of ([ValueShifts]): every estimate finite, its variance with it.
 */
internal object Firth {
    /**
     * The estimate of the [points] — log-odds and word — and the variance of each part the answers leave; of answers
     * whose log-odds are all alike, which tell no slope, the shift alone, the slope the facet's, its variance none.
     */
    fun fit(points: List<Pair<Double, Boolean>>): ValueShifts.Fitted {
        var a = 1.0
        var b = 0.0
        var info = Information.of(points, a, b)
        var step = 0
        var settled = false
        while (info.tellsSlope && !settled && step++ < STEPS) {
            var ua = 0.0
            var ub = 0.0
            points.forEach { (x, yes) ->
                val p = Calibration.sigmoid(a * x + b)
                val r = (if (yes) 1 else 0) - p + info.leverage(x, p) * (HALF - p)
                ua += r * x
                ub += r
            }
            val (da, db) = info.solve(ua, ub)
            val longest = maxOf(abs(da), abs(db))
            val scale = if (longest > MOST_STEP) MOST_STEP / longest else 1.0
            a += da * scale
            b += db * scale
            info = Information.of(points, a, b)
            settled = longest < SETTLED
        }
        return if (info.tellsSlope) ValueShifts.Fitted(a, b, info.slopeVariance, info.shiftVariance)
        else shiftOnly(points)
    }

    /** The shift alone of the [points], the slope the facet's (1), its variance none: they tell no slope. */
    private fun shiftOnly(points: List<Pair<Double, Boolean>>): ValueShifts.Fitted {
        var b = 0.0
        var weight = 0.0
        for (step in 0 until STEPS) {
            weight = points.sumOf { (x, _) -> Calibration.sigmoid(x + b).let { it * (1 - it) } }
            if (weight <= 0) return ValueShifts.Fitted(1.0, b, Double.MAX_VALUE, Double.MAX_VALUE)
            val u = points.sumOf { (x, yes) ->
                val p = Calibration.sigmoid(x + b)
                (if (yes) 1 else 0) - p + p * (1 - p) / weight * (HALF - p)
            }
            val move = (u / weight).coerceIn(-MOST_STEP, MOST_STEP)
            b += move
            if (abs(move) < SETTLED) break
        }
        return ValueShifts.Fitted(1.0, b, Double.MAX_VALUE, if (weight > 0) 1 / weight else Double.MAX_VALUE)
    }

    /** The information of the slope and the shift at a point: the sums of the weights of the answers. */
    private class Information(val aa: Double, val ab: Double, val bb: Double) {
        private val det = aa * bb - ab * ab

        /** Whether the answers tell a slope apart from the shift: their log-odds not all alike. */
        val tellsSlope: Boolean
            get() = det > ALIKE * aa * bb

        val slopeVariance: Double
            get() = if (tellsSlope) bb / det else Double.MAX_VALUE

        val shiftVariance: Double
            get() = if (tellsSlope) aa / det else Double.MAX_VALUE

        /** The leverage of an answer of log-odds [x] and chance [p]: how much it alone moves its own fit. */
        fun leverage(x: Double, p: Double): Double = p * (1 - p) * (bb * x * x - 2 * ab * x + aa) / det

        /** The move of the slope and the shift of the scores [ua] and [ub]: the information's inverse times them. */
        fun solve(ua: Double, ub: Double): Pair<Double, Double> = (bb * ua - ab * ub) / det to (aa * ub - ab * ua) / det

        companion object {
            fun of(points: List<Pair<Double, Boolean>>, a: Double, b: Double): Information {
                var aa = 0.0
                var ab = 0.0
                var bb = 0.0
                points.forEach { (x, _) ->
                    val p = Calibration.sigmoid(a * x + b)
                    val w = p * (1 - p)
                    aa += w * x * x
                    ab += w * x
                    bb += w
                }
                return Information(aa, ab, bb)
            }
        }
    }

    /** Half: Firth's adjustment of an answer moves its word toward one half by its leverage. */
    private const val HALF = 0.5

    private const val STEPS = 50

    /**
     * The longest move of a step on the scale of the log-odds, as logistf's (`maxstep`): far from the estimate the
     * penalized likelihood is flat, and Newton's step would overshoot.
     */
    private const val MOST_STEP = 5.0

    /** A move so short the estimate is reached. */
    private const val SETTLED = 1e-8

    /**
     * The determinant of the information under this share of the product of its diagonal: the answers' log-odds alike
     * to the last digits, no slope told apart from the shift.
     */
    private const val ALIKE = 1e-9
}
