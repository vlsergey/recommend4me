package io.github.vlsergey.recommend4me.size

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * What a work tells of how long its words are: its [features] — the site, the language, the normalized
 * tags and authors — and the [words] of its [chars] known, of its text or of what the sites state.
 */
class SizeExample(val features: Collection<String>, val words: Double, val chars: Double) {
    val logRatio: Double get() = ln(words / chars)

    /** What the example weighs: a short text tells its ratio by few words. */
    val weight: Double get() = chars / (chars + WordsPerChar.SAMPLE)
}

/**
 * HOW MANY WORDS A WORK HAS TO A CHARACTER, spaces among the characters — a ridge regression of the
 * logarithm of the ratio over a work's features: of all the works one mean and each feature's step from
 * it, an author's words saying much of a work and a tag's less, each by its own penalty. A work's own
 * numbers weigh over the model's by how many characters they count ([of]).
 */
class WordsPerChar(private val weights: Map<String, Double>) {
    /** The logarithm of the ratio of a work of these features. */
    fun log(features: Collection<String>): Double = (weights[MEAN] ?: 0.0) + features.sumOf { weights[it] ?: 0.0 }

    /** The ratio of a work of these features, of its [own] numbers when it has them. */
    fun of(features: Collection<String>, own: SizeExample?): Double {
        val model = log(features)
        return exp(if (own == null) model else (own.chars * own.logRatio + SAMPLE * model) / (own.chars + SAMPLE))
    }

    companion object {
        /** The characters a work's own numbers weigh as much as the model by. */
        const val SAMPLE = 5000.0

        const val SOURCE = "source:"
        const val LANGUAGE = "language:"
        const val TAG = "tag:"
        const val AUTHOR = "author:"

        private const val MEAN = "mean"
        private const val ITERATIONS = 2000
        private const val TOLERANCE = 1e-20
        private const val FOLDS = 10

        /**
         * The penalty of a feature's weight, found by the cross-validation over the works known on
         * 2026-10-30: the mean nearly free, a language — few works of any but the first — lightly held, an
         * author a little more, a tag — one of many that say little — most.
         */
        private fun penalty(feature: String) = when {
            feature == MEAN -> 1e-6
            feature.startsWith(LANGUAGE) -> 0.1
            feature.startsWith(AUTHOR) -> 1.0
            feature.startsWith(SOURCE) -> 3.0
            else -> 10.0
        }

        /** The model of the [examples]: the normal equations of the weighed ridge solved by conjugate gradients. */
        fun fit(examples: List<SizeExample>): WordsPerChar {
            val index = HashMap<String, Int>()
            index[MEAN] = 0
            val rows = examples.map { e -> (e.features.map { f -> index.getOrPut(f) { index.size } } + 0).distinct().toIntArray() }
            val n = index.size
            val lambda = DoubleArray(n)
            index.forEach { (f, i) -> lambda[i] = penalty(f) }
            val weight = DoubleArray(examples.size) { examples[it].weight }
            fun times(x: DoubleArray): DoubleArray {
                val out = DoubleArray(n) { lambda[it] * x[it] }
                rows.forEachIndexed { i, row ->
                    val s = weight[i] * row.sumOf { x[it] }
                    row.forEach { out[it] += s }
                }
                return out
            }
            val x = DoubleArray(n)
            val r = DoubleArray(n)
            rows.forEachIndexed { i, row -> row.forEach { r[it] += weight[i] * examples[i].logRatio } }
            val p = r.copyOf()
            var rs = r.sumOf { it * it }
            for (step in 1..ITERATIONS) {
                if (rs < TOLERANCE) break
                val ap = times(p)
                val alpha = rs / p.indices.sumOf { p[it] * ap[it] }
                for (i in 0 until n) {
                    x[i] += alpha * p[i]
                    r[i] -= alpha * ap[i]
                }
                val next = r.sumOf { it * it }
                for (i in 0 until n) p[i] = r[i] + next / rs * p[i]
                rs = next
            }
            return WordsPerChar(index.entries.associate { (f, i) -> f to x[i] })
        }

        /** How far from the truth the logarithm of the ratio is told of a work the model did not see: the mean square of the folds' errors, its root. */
        fun outOfFold(examples: List<SizeExample>): Double {
            if (examples.size < FOLDS) return Double.NaN
            val shuffled = examples.shuffled(Random(1))
            var sum = 0.0
            for (fold in 0 until FOLDS) {
                val model = fit(shuffled.filterIndexed { i, _ -> i % FOLDS != fold })
                shuffled.forEachIndexed { i, e ->
                    if (i % FOLDS == fold) sum += (e.logRatio - model.log(e.features)).let { it * it }
                }
            }
            return sqrt(sum / shuffled.size)
        }
    }
}

/** How a text's words are counted as the sites count them: letters and digits, a hyphen or an apostrophe within them part of the word — ficbook's count to a tenth of a percent, and a dash standing alone no word. */
object WordCount {
    fun of(text: String): Int {
        var words = 0
        var inWord = false
        for (i in text.indices) {
            val c = text[i]
            if (c.isLetterOrDigit()) {
                if (!inWord) words++
                inWord = true
            } else if (!inWord || c !in WITHIN || i + 1 == text.length || !text[i + 1].isLetterOrDigit()) inWord = false
        }
        return words
    }

    private const val WITHIN = "-'’‐‑"
}
