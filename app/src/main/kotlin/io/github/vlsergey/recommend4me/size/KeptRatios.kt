package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.duplicate.ComparedValues
import io.github.vlsergey.recommend4me.part.CharClasses
import kotlin.math.exp

/**
 * THE MODEL OF THE WORDS TO A CHARACTER AS ITS READERS USE IT: its weight of every feature by the feature's name, as it
 * was kept ([WorkSizeRepository]). A site item's features are its site and the values the index of the list holds of
 * it, unified across the sites as the search of duplicates compares them ([ComparedValues]) — its tags, its languages,
 * its normalized authors (owner, 2026-11-20: "let it be unified"; "for the duplicates we need a site item and its size,
 * not a work's"): what a reader of the index has of every item already, no ratio kept of every work.
 */
class KeptRatios(private val weights: Map<String, Double>) {
    /** Whether a model was fitted and kept. */
    val fitted: Boolean
        get() = weights.isNotEmpty()

    /** The characters a work's own count weighs as much as the model by ([WordsPerChar.sample]). */
    private val sample = weights[WordsPerChar.SAMPLE] ?: 0.0

    /** How the site [source] counts the characters ([CharRule]), as kept; none of a site that states none. */
    private fun ruleOf(source: String): CharRule? {
        val coefficients = DoubleArray(CharClasses.NAMES.size) { weights[CharRule.nameOf(source, it)] ?: Double.NaN }
        return if (coefficients.any { it.isNaN() }) null else CharRule(coefficients)
    }

    /** The weight of the feature [name]; nought of one the model did not learn a step of. */
    private fun weightOf(name: String?): Double = name?.let { weights[it] } ?: 0.0

    /**
     * The ratio of every item [compared] of the [source] — by the places of [ComparedValues.Compared.ids] —, of the
     * model, and of its own counted text ([textsOf]: the words and the characters of the items that have one; their
     * characters as the site counts them, of their classes [classesOf] by the site's rule) weighing over the model by
     * how many characters it counts; of no model kept, none.
     */
    internal fun of(
        source: String,
        compared: ComparedValues.Compared,
        /** The words and the characters of the items that have a text counted, read only of a model kept. */
        textsOf: () -> Map<String, Pair<Double, Double>>,
        /** The classes of the characters of their texts, read only of a site of a rule kept. */
        classesOf: () -> Map<String, IntArray>,
    ): DoubleArray? {
        if (!fitted) return null
        val rule = ruleOf(source)
        val classes = rule?.let { classesOf() }.orEmpty()
        val texts =
            textsOf().mapValues { (id, own) ->
                val counted = rule?.let { r ->
                    classes[id]?.let { c -> r.of(DoubleArray(c.size) { c[it].toDouble() }) }
                }
                if (counted != null && counted > 0) own.first to counted else own
            }
        // A value's weight looked up once a value, not once an item
        val byValue = DoubleArray(compared.names.size) { weightOf(compared.names[it]) }
        val base = weightOf(WordsPerChar.MEAN) + weightOf(WordsPerChar.SOURCE + source)
        return DoubleArray(compared.ids.size) { i ->
            var log = base
            for (v in compared.numbered[i]) log += byValue[v]
            val own = texts[compared.ids[i]]?.takeIf { it.first > 0 && it.second > 0 }
            exp(if (own == null) log else WordsPerChar.blended(own.first, own.second, log, sample))
        }
    }
}
