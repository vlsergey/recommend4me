package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.part.CharClasses
import kotlin.math.abs
import kotlin.math.max

/**
 * HOW A SITE COUNTS THE CHARACTERS OF A WORK, as a sum of the classes of ours ([CharClasses]), each by a coefficient of
 * the site's own (owner, 2026-11-20: "write it into the model as a part of the formula"): one of a class it counts as
 * we do, two of a class it counts by its bytes, nought of one it leaves out. Worked out of the items both tell of — the
 * site's number and our text — as the least absolute deviations, every coefficient nought or more: a text we keep of
 * fewer chapters than the site counts stands off the line and weighs nothing for that. The sum by the coefficients is
 * the item's characters as the site counts them ([of]).
 */
internal class CharRule(val coefficients: DoubleArray) {

    /** The characters of a text of the [classes], as the site counts them. */
    fun of(classes: DoubleArray): Double {
        var sum = 0.0
        for (k in coefficients.indices) sum += coefficients[k] * classes[k]
        return sum
    }

    override fun toString() =
        CharClasses.NAMES.indices.joinToString(" ") { "${CharClasses.NAMES[it]} %.3f".format(coefficients[it]) }

    /** The coefficients by the names they are kept by among the model's weights ([nameOf]). */
    fun named(source: String): Map<String, Double> =
        coefficients.indices.associate { nameOf(source, it) to coefficients[it] }

    companion object {
        /** The name the coefficient of the class [k] of the site [source] is kept by: no feature is named so. */
        fun nameOf(source: String, k: Int) = "chars:$source:${CharClasses.NAMES[k]}"

        /** The rule of the items' [classes] and the site's [stated] counts of them; none of no item. */
        fun of(classes: List<DoubleArray>, stated: List<Double>): CharRule? {
            if (classes.isEmpty()) return null
            val n = CharClasses.NAMES.size
            var x = DoubleArray(n) { 1.0 }
            var weights = DoubleArray(classes.size) { 1.0 }
            for (round in 1..MOST_ROUNDS) {
                val next = nonNegative(classes, stated, weights, x)
                // The least absolute deviations by least squares weighed again: each by the inverse of its residual
                val residuals = DoubleArray(classes.size) { abs(stated[it] - CharRule(next).of(classes[it])) }
                val floor = max(residuals.average() * FLOOR, Double.MIN_VALUE)
                weights = DoubleArray(classes.size) { 1 / max(residuals[it], floor) }
                val moved = next.indices.maxOf { abs(next[it] - x[it]) / maxOf(abs(next[it]), abs(x[it]), FLOOR) }
                x = next
                if (moved < CHANGE) break
            }
            return CharRule(x)
        }

        /**
         * The weighted least squares of the [classes] to the [stated], every coefficient nought or more, begun at
         * [from].
         */
        private fun nonNegative(
            classes: List<DoubleArray>,
            stated: List<Double>,
            weights: DoubleArray,
            from: DoubleArray,
        ): DoubleArray {
            val n = from.size
            val gram = Array(n) { DoubleArray(n) }
            val right = DoubleArray(n)
            classes.forEachIndexed { i, c ->
                for (a in 0 until n) {
                    right[a] += weights[i] * c[a] * stated[i]
                    for (b in 0 until n) gram[a][b] += weights[i] * c[a] * c[b]
                }
            }
            val x = from.copyOf()
            // Coordinate descent, every coefficient held to nought or more
            for (sweep in 1..MOST_SWEEPS) {
                var moved = 0.0
                for (a in 0 until n) {
                    if (gram[a][a] <= 0) {
                        x[a] = 0.0
                        continue
                    }
                    var g = -right[a]
                    for (b in 0 until n) g += gram[a][b] * x[b]
                    val next = max(0.0, x[a] - g / gram[a][a])
                    moved = max(moved, abs(next - x[a]) / max(abs(next), FLOOR))
                    x[a] = next
                }
                if (moved < CHANGE) break
            }
            return x
        }

        /** How little the coefficients move when they stand: half a percent (owner, 2026-11-20). */
        private const val CHANGE = 0.005

        /** A residual below so much of the mean one weighs as much: no item weighs without end. */
        private const val FLOOR = 1e-6

        private const val MOST_ROUNDS = 50
        private const val MOST_SWEEPS = 1000
    }
}
