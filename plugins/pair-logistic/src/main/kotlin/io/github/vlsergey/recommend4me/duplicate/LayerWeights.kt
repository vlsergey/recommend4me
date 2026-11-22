package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.AUTHORS
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.AUTHORS_HOLDING
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.AUTHORS_WITHIN
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.CONSTANT
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.DESCRIPTION
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.PARTLY_SAME_AUTHOR
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.SAME_AUTHOR
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.SIZE
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.TITLE
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.TITLE_HOLDING
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.TITLE_VECTOR
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.TITLE_WITHIN
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.facet
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.has
import io.github.vlsergey.recommend4me.duplicate.Logistic.Companion.letters

/**
 * THE WEIGHTS OF A LEARNT MODEL AS A PAIR OF [PairLayers] IS SCORED: every feature's weight looked up once, when the
 * model is made — of every layer, of every text compared letter by letter, of every facet by its name and of every two
 * sites —, the score of a pair their sum over its layers, no feature named of it. The score by the names of the
 * features ([Logistic.features]: a map of some thirty a pair, the names of its facets and sites made anew) took 21 s of
 * the 2.6 million pairs of a search of the books, scored twice (2026-11-22); what the [weights] give is the same.
 */
internal class LayerWeights(weights: Map<String, Double>) {
    private fun Map<String, Double>.of(name: String) = this[name] ?: 0.0

    private val constant = weights.of(CONSTANT)
    private val title = weights.of(TITLE)
    private val titleWithin = weights.of(TITLE_WITHIN)
    private val titleHolding = weights.of(TITLE_HOLDING)
    private val authors = weights.of(AUTHORS)
    private val authored = weights.of(has(AUTHORS))
    private val authorsWithin = weights.of(AUTHORS_WITHIN)
    private val authorsHolding = weights.of(AUTHORS_HOLDING)
    private val sameAuthor = weights.of(SAME_AUTHOR)
    private val partlySameAuthor = weights.of(PARTLY_SAME_AUTHOR)
    private val titleVector = weights.of(TITLE_VECTOR)
    private val titled = weights.of(has(TITLE_VECTOR))
    private val description = weights.of(DESCRIPTION)
    private val described = weights.of(has(DESCRIPTION))
    private val size = weights.of(SIZE)
    private val sized = weights.of(has(SIZE))
    private val letters = DoubleArray(LETTERED) { weights.of(letters(LetteredText.entries[it])) }
    private val lettered = DoubleArray(LETTERED) { weights.of(has(letters(LetteredText.entries[it]))) }

    /** Of every facet by its name, its weight and that of both having it ([Logistic.facet]). */
    private val facet = HashMap<String, Double>()
    private val faceted = HashMap<String, Double>()

    /** Of every two sites, both ways, their weight ([Logistic.sites]). */
    private val sites = HashMap<String, HashMap<String, Double>>()

    init {
        val prefix = facet("")
        weights.forEach { (name, w) ->
            when {
                name.startsWith(prefix) && name.endsWith(has("")) ->
                    faceted[name.substring(prefix.length, name.length - has("").length)] = w
                name.startsWith(prefix) -> facet[name.substring(prefix.length)] = w
                name.startsWith(Logistic.SITES) -> {
                    val (site, other) = name.substring(Logistic.SITES.length).split(Logistic.SITES_APART, limit = 2)
                    sites.getOrPut(site) { HashMap() }[other] = w
                    sites.getOrPut(other) { HashMap() }[site] = w
                }
            }
        }
    }

    /** The score of the pair of the [layers]: the sum of the weights of the features it has, by their values. */
    fun score(layers: PairLayers): Double =
        constant +
            title * layers.title +
            titleWithin * layers.titleWithin +
            titleHolding * layers.titleHolding +
            (sites[layers.site]?.get(layers.otherSite) ?: 0.0) +
            ofAuthors(layers) +
            ofTexts(layers) +
            ofFacets(layers)

    private fun ofAuthors(layers: PairLayers): Double {
        var z = 0.0
        layers.authors?.let {
            z += authors * it.alike + authored + authorsWithin * it.within + authorsHolding * it.holding
        }
        if (layers.sameAuthor) z += sameAuthor
        if (layers.partlySameAuthor) z += partlySameAuthor
        return z
    }

    private fun ofTexts(layers: PairLayers): Double {
        var z = 0.0
        layers.titleVector?.let { z += titleVector * it + titled }
        layers.letters.forEach { (text, v) -> z += letters[text.ordinal] * v + lettered[text.ordinal] }
        layers.description?.let { z += description * it + described }
        layers.size?.let { z += size * it + sized }
        return z
    }

    private fun ofFacets(layers: PairLayers): Double {
        var z = 0.0
        layers.facets.forEach { (family, v) -> z += (facet[family] ?: 0.0) * v + (faceted[family] ?: 0.0) }
        return z
    }
}
