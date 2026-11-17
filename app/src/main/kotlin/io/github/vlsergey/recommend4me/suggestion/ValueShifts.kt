package io.github.vlsergey.recommend4me.suggestion

import kotlin.math.abs

/**
 * EVERY VALUE'S OWN SLOPE AND SHIFT OVER THE FACET'S CURVE ([Calibration]), on the scale of the log-odds: of its
 * answers' log-odds by the facet's curve `x` and their words, the log-odds of the value's chance `slope·x + shift`.
 * Drawn to the facet's curve (slope 1, shift 0) by a spread learnt of all the values (the empirical Bayes): of how far
 * the values' own estimates lie from it beyond what their few answers alone would scatter them. A value of no answer is
 * the facet's curve, of a few between, of many its own; when the values are not seen to differ, every one is the
 * facet's.
 */
internal object ValueShifts {

    /** The values' shifts of the [answered], [x] the log-odds of an answer by the facet's curve; none of no answer. */
    fun of(answered: List<Answered>, x: (Answered) -> Double): Map<String, Calibration.Shift> {
        val byValue = answered.groupBy { it.value }.mapValues { (_, list) -> list.map { x(it) to it.yes } }
        // Of every value of two answers or more, its own estimate and how much its answers leave it uncertain
        val own = byValue.values.filter { it.size >= 2 }.map { fit(it, LOOSE, LOOSE) }
        val slopeSpread = spread(own.map { (it.slope - 1) to it.slopeVariance })
        val shiftSpread = spread(own.map { it.shift to it.shiftVariance })
        if (slopeSpread == 0.0 && shiftSpread == 0.0) return emptyMap()
        return byValue
            .mapValues { (_, points) -> fit(points, slopeSpread, shiftSpread) }
            .filterValues { abs(it.slope - 1) > SAME || abs(it.shift) > SAME }
            .mapValues { (_, f) -> Calibration.Shift(f.slope, f.shift) }
    }

    /** A value's estimate: its slope and shift, and the variance of each the answers leave. */
    private class Fitted(val slope: Double, val shift: Double, val slopeVariance: Double, val shiftVariance: Double)

    /**
     * The spread of the values about the facet's curve, of their [estimates] — each its distance from it and the
     * variance its answers leave — by DerSimonian and Laird: each weighed by how sure its answers make it, a value of a
     * few answers all on one side, its estimate far and loose, all but unweighed; none below nought.
     */
    private fun spread(estimates: List<Pair<Double, Double>>): Double {
        val sure = estimates.filter { it.second > 0 && it.second < Double.MAX_VALUE }
        if (sure.size < 2) return 0.0
        val w = sure.map { 1 / it.second }
        val q = sure.indices.sumOf { w[it] * sure[it].first * sure[it].first }
        val scale = w.sum() - w.sumOf { it * it } / w.sum()
        return if (scale <= 0) 0.0 else ((q - sure.size) / scale).coerceAtLeast(0.0)
    }

    /**
     * The slope and the shift of the [points] — log-odds and word — most likely with the slope drawn to 1 by the
     * variance [slopeSpread] and the shift to 0 by [shiftSpread] (nought: held there), by Newton's steps.
     */
    private fun fit(points: List<Pair<Double, Boolean>>, slopeSpread: Double, shiftSpread: Double): Fitted {
        var a = 1.0
        var b = 0.0
        var sums = Sums.of(points, a, b, slopeSpread, shiftSpread)
        repeat(STEPS) {
            val (da, db) = sums.step(slopeSpread > 0, shiftSpread > 0)
            // A higher score never the lower chance
            a = (a - da).coerceAtLeast(LEAST_SLOPE)
            b -= db
            sums = Sums.of(points, a, b, slopeSpread, shiftSpread)
        }
        return Fitted(a, b, sums.slopeVariance, sums.shiftVariance)
    }

    /**
     * The first and second derivatives of the less likelihood of a value's points at a slope and a shift, the pull to
     * the facet's curve among them.
     */
    private class Sums(val ga: Double, val gb: Double, val haa: Double, val hbb: Double, val hab: Double) {
        private val det = haa * hbb - hab * hab

        val slopeVariance: Double
            get() = if (det > 0) hbb / det else Double.MAX_VALUE

        val shiftVariance: Double
            get() = if (det > 0) haa / det else Double.MAX_VALUE

        /** Newton's step of the slope and the shift, of those [slope] and [shift] free; none of a flat likelihood. */
        fun step(slope: Boolean, shift: Boolean): Pair<Double, Double> =
            when {
                slope && shift && det > 0 -> (hbb * ga - hab * gb) / det to (haa * gb - hab * ga) / det
                slope && !shift && haa > 0 -> ga / haa to 0.0
                shift && !slope && hbb > 0 -> 0.0 to gb / hbb
                else -> 0.0 to 0.0
            }

        companion object {
            fun of(
                points: List<Pair<Double, Boolean>>,
                a: Double,
                b: Double,
                slopeSpread: Double,
                shiftSpread: Double,
            ): Sums {
                var ga = if (slopeSpread > 0) (a - 1) / slopeSpread else 0.0
                var gb = if (shiftSpread > 0) b / shiftSpread else 0.0
                var haa = if (slopeSpread > 0) 1 / slopeSpread else 0.0
                var hbb = if (shiftSpread > 0) 1 / shiftSpread else 0.0
                var hab = 0.0
                points.forEach { (x, yes) ->
                    val p = Calibration.sigmoid(a * x + b)
                    val r = p - if (yes) 1 else 0
                    val w = p * (1 - p)
                    ga += r * x
                    gb += r
                    haa += w * x * x
                    hbb += w
                    hab += w * x
                }
                return Sums(ga, gb, haa, hbb, hab)
            }
        }
    }

    /** The variance of a value's own estimate: drawn to the facet's curve so little it is all but its own. */
    private const val LOOSE = 1e6

    private const val STEPS = 30

    /** The least slope: a higher score is never the lower chance. */
    private const val LEAST_SLOPE = 1e-3

    /** A slope and a shift so near the facet's curve the value is the facet's. */
    private const val SAME = 1e-6
}
