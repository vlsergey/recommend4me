package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.duplicate.AUTHOR_FAMILY
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * THE FEATURES OF THE WORKS' SIZES BY THEIR NUMBERS: the mean the first ([WordsPerChar.MEAN]), then every site,
 * language, normalized tag and author as it is met — a work's features numbers, not strings: of 1.4 million works a
 * string and a set a feature of every work (2026-11-20).
 */
class SizeFeatures {
    private val byName = HashMap<String, Int>()
    val names = ArrayList<String>()

    init {
        id(WordsPerChar.MEAN)
    }

    /** The number of the feature [name], given at its first meeting. */
    fun id(name: String): Int = byName.getOrPut(name) { names.size.also { names += name } }
}

/**
 * What a work tells of how long its words are: its [features] by their numbers ([SizeFeatures]) — the site, the
 * language, the normalized tags and authors, each once —, and the [words] of its [chars] known, of its text or of what
 * the sites state.
 */
class SizeExample(val features: IntArray, val words: Double, val chars: Double) {
    val logRatio: Double
        get() = ln(words / chars)

    /** What the example weighs: a short text tells its ratio by few words. */
    val weight: Double
        get() = chars / (chars + WordsPerChar.SAMPLE)
}

/**
 * HOW MANY WORDS A WORK HAS TO A CHARACTER, spaces among the characters — a ridge regression of the logarithm of the
 * ratio over a work's features: of all the works one mean and each feature's step from it, an author's words saying
 * much of a work and a tag's less, each by its own penalty. A work's own numbers weigh over the model's by how many
 * characters they count ([of]).
 */
class WordsPerChar(private val weights: DoubleArray) {
    /** The logarithm of the ratio of a work of these features: a feature met after the fitting, none. */
    fun log(features: IntArray): Double {
        var sum = weights[0]
        for (f in features) if (f < weights.size) sum += weights[f]
        return sum
    }

    /** The ratio of a work of these features, of its [own] numbers when it has them. */
    fun of(features: IntArray, own: SizeExample?): Double {
        val model = log(features)
        return exp(if (own == null) model else blended(own.words, own.chars, model))
    }

    /** The weight of every feature the model learnt a step of, by the feature's name of [names]: what is kept. */
    fun named(names: List<String>): Map<String, Double> =
        weights.indices.filter { weights[it] != 0.0 }.associate { names[it] to weights[it] }

    companion object {
        /**
         * The logarithm of the ratio of a text of its own [words] and [chars], and of the [model]'s: its own weighing
         * over the model's by how many characters it counts.
         */
        fun blended(words: Double, chars: Double, model: Double): Double =
            (chars * ln(words / chars) + SAMPLE * model) / (chars + SAMPLE)

        /** The characters a work's own numbers weigh as much as the model by. */
        const val SAMPLE = 5000.0

        /**
         * The beginnings of the features' names: a site's, and of the values of the index as the search of duplicates
         * compares them ([io.github.vlsergey.recommend4me.duplicate.ComparedValues]) — the languages and the normalized
         * authors; any other value, a tag.
         */
        const val SOURCE = "source:"
        const val LANGUAGE = StandardLanguages.KEY + ":"
        const val AUTHOR = AUTHOR_FAMILY + ":"

        /** The feature every work has, the first of [SizeFeatures]. */
        const val MEAN = "mean"
        private const val ITERATIONS = 2000
        private const val TOLERANCE = 1e-20
        private const val FOLDS = 10

        /**
         * The penalty of a feature's weight, found by the cross-validation over the works known on 2026-10-30: the mean
         * nearly free, a language — few works of any but the first — lightly held, an author a little more, a tag — one
         * of many that say little — most.
         */
        private fun penalty(feature: String) =
            when {
                feature == MEAN -> MEAN_PENALTY
                feature.startsWith(LANGUAGE) -> LANGUAGE_PENALTY
                feature.startsWith(AUTHOR) -> AUTHOR_PENALTY
                feature.startsWith(SOURCE) -> SOURCE_PENALTY
                else -> TAG_PENALTY
            }

        /** The penalties of [penalty], by what the feature is. */
        private const val MEAN_PENALTY = 1e-6
        private const val LANGUAGE_PENALTY = 0.1
        private const val AUTHOR_PENALTY = 1.0
        private const val SOURCE_PENALTY = 3.0
        private const val TAG_PENALTY = 10.0

        /**
         * The model of the [examples], their features named by [names]: the normal equations of the weighed ridge
         * solved by conjugate gradients. Every example of the mean and its own features, read as they are: no row made
         * of them.
         */
        fun fit(examples: List<SizeExample>, names: List<String>): WordsPerChar {
            val n = names.size
            val lambda = DoubleArray(n) { penalty(names[it]) }
            val weight = DoubleArray(examples.size) { examples[it].weight }
            fun times(x: DoubleArray): DoubleArray {
                val out = DoubleArray(n) { lambda[it] * x[it] }
                examples.forEachIndexed { i, e ->
                    var sum = x[0]
                    for (f in e.features) sum += x[f]
                    val s = weight[i] * sum
                    out[0] += s
                    for (f in e.features) out[f] += s
                }
                return out
            }
            val x = DoubleArray(n)
            val r = DoubleArray(n)
            examples.forEachIndexed { i, e ->
                val s = weight[i] * e.logRatio
                r[0] += s
                for (f in e.features) r[f] += s
            }
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
            return WordsPerChar(x)
        }

        /**
         * How far from the truth the logarithm of the ratio is told of a work the model did not see: the mean square of
         * the folds' errors, its root.
         */
        fun outOfFold(examples: List<SizeExample>, names: List<String>): Double {
            if (examples.size < FOLDS) return Double.NaN
            val shuffled = examples.shuffled(Random(1))
            var sum = 0.0
            for (fold in 0 until FOLDS) {
                val model = fit(shuffled.filterIndexed { i, _ -> i % FOLDS != fold }, names)
                shuffled.forEachIndexed { i, e ->
                    if (i % FOLDS == fold) sum += (e.logRatio - model.log(e.features)).let { it * it }
                }
            }
            return sqrt(sum / shuffled.size)
        }
    }
}
