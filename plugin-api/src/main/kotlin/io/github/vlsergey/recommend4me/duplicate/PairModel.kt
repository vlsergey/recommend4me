package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas

/**
 * How alike two works of different sites are, layer by layer — what a model of pairs reads: the cosines of the vectors
 * of their titles and of their descriptions (none of a work without one), how near their sizes are — of every number
 * both have, the smaller over the larger, their mean (none when they share none) —, of every facet the sites share both
 * have the cosine of their values weighed by rarity ([facets], by the facet), and whether the dictionary of authors
 * gives them one author, fully or in part.
 */
class PairLayers(
    val title: Float,
    val description: Float?,
    val size: Float?,
    val facets: Map<String, Float>,
    val sameAuthor: Boolean,
)

/** A model of pairs fitted: how likely a pair of [PairLayers] is of its kind — one work, one series. */
interface FittedPairModel {
    fun chance(layers: PairLayers): Double

    /** Its state, for [PairModel.unpack] to read again. */
    fun pack(): ByteArray
}

/**
 * A pair a search kept: of the [site]'s [item]-th work (by the places of [PairSearch.sites] and of [SiteWorks.ids]),
 * the [otherSite]'s [other]-th, the likeliest of that site of the kinds whose bits [kinds] has — a bit a model, by its
 * place among those the search was given.
 */
class FoundPair(val site: Int, val item: Int, val otherSite: Int, val other: Int, val kinds: Int)

/**
 * A WAY OF TELLING HOW LIKELY A PAIR OF WORKS OF DIFFERENT SITES IS OF A KIND — one work, one series of works — of how
 * alike their layers are ([PairLayers]), learnt from the user's answers on pairs; and THE SEARCH of the pairs by it.
 * The application fits one model a kind, every one the same way, and asks the search for all of them at once: every
 * pair of works compared once, scored by every model.
 */
interface PairModel {
    /** Stable: kept with the fitted states. */
    val id: String

    /** What the model says of a pair before any answer that a pair is of the kind. */
    fun prior(): FittedPairModel

    /**
     * Learns of the [pairs] by the user's word on each ([said], in their order): true — of the kind, false — not, null
     * — not answered; nearly every pair a search keeps is not of the kind. Null when no pair is said to be of it.
     */
    fun fit(pairs: List<PairLayers>, said: List<Boolean?>): FittedPairModel?

    /** A state kept by [FittedPairModel.pack]; null when it is not of this model's making. */
    fun unpack(bytes: ByteArray): FittedPairModel?

    /**
     * THE PAIRS OF THE [search]: of every work of every site, of every other site the work likeliest by each of the
     * [models] (of this model's making: [prior], [fit], [unpack]) — every pair of two sites compared once, its layers
     * made and scored by every model at once —, the works the user answered on passed over ([PairSearch.answered]). On
     * the [card] when there is one. Of a work and another site, one [FoundPair] of every other work that is the
     * likeliest by some model, its [FoundPair.kinds] the bits of those models; found for the work ([FoundPair.site],
     * [FoundPair.item]). A work with no other work left to compare with: none.
     */
    fun search(search: PairSearch, models: List<FittedPairModel>, card: Cublas?): List<FoundPair>
}
