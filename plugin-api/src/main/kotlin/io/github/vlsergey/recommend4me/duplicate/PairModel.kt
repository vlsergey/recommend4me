package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas

/**
 * How alike two works are, of two sites or of one, layer by layer — what a model of pairs reads: how alike their titles
 * are letter by letter ([title], the cosine of their trigrams — user, 2026-11-18: the titles and the authors compared
 * as strings, not as vectors of meaning — and how much of either is the other's: [titleWithin], [titleHolding]), the
 * cosine of the vectors of their descriptions (none of a work without one), how near their sizes are — of every number
 * both have, the smaller over the larger, their mean (none when they share none) —, of every facet the sites share both
 * have the cosine of their values weighed by rarity ([facets], by the facet), whether the dictionary of authors gives
 * them one author, fully or in part, how alike the names of their authors are letter by letter ([authors]: none when
 * either has none), and the sites they are of ([site] the first's, [otherSite] the second's: a site and itself, or two
 * sites).
 */
class PairLayers(
    val title: Float,
    val description: Float?,
    val size: Float?,
    val facets: Map<String, Float>,
    val sameAuthor: Boolean,
    val site: String,
    val otherSite: String,
    val titleWithin: Float = 0f,
    val titleHolding: Float = 0f,
    val authors: Lexical? = null,
)

/**
 * HOW ALIKE TWO STRINGS ARE LETTER BY LETTER, as near as their trigrams tell (user, 2026-11-18: approximately and
 * fast): the cosine of the counts of their trigrams ([alike]), how much of the first's trigrams are the second's
 * ([within]: 1 — the first is a substring of the second, as near as trigrams tell) and how much of the second's are the
 * first's ([holding]). The strings normalized before: lower case, every run of what is no letter nor digit one space,
 * trimmed.
 */
class Lexical(val alike: Float, val within: Float, val holding: Float) {
    companion object {
        /**
         * Of the [cosine] of two strings' trigram counts and the lengths of the counts ([first], [second]): the share
         * of either's trigrams the other has, of the product of the counts — at most one, a trigram counted twice in
         * one and once in the other, or two trigrams of one bucket, telling the share over.
         */
        fun of(cosine: Float, first: Float, second: Float): Lexical =
            Lexical(cosine, share(cosine, first, second), share(cosine, second, first))

        /** The share of the trigrams of the string of length [own] the other, of length [other], has. */
        fun share(cosine: Float, own: Float, other: Float): Float =
            if (own <= 0f) 0f else (cosine * other / own).coerceIn(0f, 1f)
    }
}

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
     * THE PAIRS OF THE [search]: of every work of every site, the work of any site — its own too, never itself —
     * likeliest by each of the [models] (of this model's making: [prior], [fit], [unpack]) — every pair compared once,
     * its layers made and scored by every model at once —, the works the user answered on passed over
     * ([PairSearch.answered]). On the [card] when there is one. Of a work, one [FoundPair] of every other work that is
     * the likeliest by some model — so as many as the models at most —, its [FoundPair.kinds] the bits of those models;
     * found for the work ([FoundPair.site], [FoundPair.item]). A work with no other work left to compare with: none.
     * How much of it is done told to [progress] as it goes, a share of the pairs compared.
     */
    fun search(
        search: PairSearch,
        models: List<FittedPairModel>,
        card: Cublas?,
        progress: (Double) -> Unit = {},
    ): List<FoundPair>
}
