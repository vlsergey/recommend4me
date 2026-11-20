package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.duplicate.AUTHOR_FAMILY
import kotlin.math.exp
import kotlin.math.ln

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
}

/**
 * HOW MANY WORDS A WORK HAS TO A CHARACTER, spaces among the characters — a ridge regression of the logarithm of the
 * ratio over a work's features: of all the works one mean and each feature's step from it, each kind of feature held by
 * a penalty of its own; the penalties, and how much a work's own text weighs, worked out of the data ([SizeVariances])
 * — no number of the code's own (owner, 2026-11-20). A work's own numbers weigh over the model's by how many characters
 * they count: as much as [sample] of them weigh as the model.
 */
class WordsPerChar(internal val weights: DoubleArray, val sample: Double) {
    /** The logarithm of the ratio of a work of these features: a feature met after the fitting, none. */
    fun log(features: IntArray): Double {
        var sum = weights[0]
        for (f in features) if (f < weights.size) sum += weights[f]
        return sum
    }

    /** The ratio of a work of these features, of its [own] numbers when it has them. */
    fun of(features: IntArray, own: SizeExample?): Double {
        val model = log(features)
        return exp(if (own == null) model else blended(own.words, own.chars, model, sample))
    }

    /**
     * The weight of every feature the model learnt a step of, by the feature's name of [names], and its [sample] by
     * [SAMPLE]: what is kept.
     */
    fun named(names: List<String>): Map<String, Double> =
        weights.indices.filter { weights[it] != 0.0 }.associate { names[it] to weights[it] } + (SAMPLE to sample)

    companion object {
        /**
         * The logarithm of the ratio of a text of its own [words] and [chars], and of the [model]'s: its own weighing
         * over the model's as its characters to the [sample]'s.
         */
        fun blended(words: Double, chars: Double, model: Double, sample: Double): Double =
            (chars * ln(words / chars) + sample * model) / (chars + sample)

        /** The name [sample] is kept by among the weights: no feature is named so. */
        const val SAMPLE = "model.sample"

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

        /** The kinds of features, each of a penalty of its own: the sites, the languages, the authors, the tags. */
        val KINDS = listOf("sites", "languages", "authors", "tags")

        /** The kind of every feature of [names], by [KINDS]; of the mean, none (-1): it is held by no penalty. */
        fun kindsOf(names: List<String>): IntArray =
            IntArray(names.size) {
                val name = names[it]
                when {
                    name == MEAN -> -1
                    name.startsWith(SOURCE) -> 0
                    name.startsWith(LANGUAGE) -> 1
                    name.startsWith(AUTHOR) -> 2
                    else -> KINDS.size - 1
                }
            }

        /** The model of the [examples], their features named by [names], fitted on the processor ([SizeVariances]). */
        fun fit(examples: List<SizeExample>, names: List<String>): WordsPerChar {
            val rows = SizeRows(examples, names.size)
            val fitted = SizeVariances(rows, kindsOf(names), SizeRidge(rows, SizeGram.OnProcessor(rows))).fit()
            return WordsPerChar(fitted.x, fitted.variances.sample)
        }

        /**
         * How far from the truth the logarithm of the ratio is told of a work the model did not see: the root of the
         * mean square of the errors on the tenth of the examples held out of its fitting; on the processor.
         */
        fun heldError(examples: List<SizeExample>, names: List<String>): Double {
            val rows = SizeRows(examples, names.size)
            return SizeVariances(rows, kindsOf(names), SizeRidge(rows, SizeGram.OnProcessor(rows))).fit().held
        }
    }
}
