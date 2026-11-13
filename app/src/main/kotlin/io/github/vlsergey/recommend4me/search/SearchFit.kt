package io.github.vlsergey.recommend4me.search

/**
 * How far a work found answers the line searched for, by the user's word: whole, in part, not at all. Of two works of a
 * line judged apart, the one of the higher [level] is to stand higher — what the search learns of the judgments
 * ([SearchWeights], [QueryShift]); [gain] is what a work brings to the order of the first ones found, the measure of
 * its quality ([SearchQuality]): 2^level − 1, a work that answers whole three of one that answers in part.
 */
enum class SearchFit(val level: Int) {
    NOT(0),
    PARTLY(1),
    FITS(2);

    val gain: Double
        get() = (1 shl level) - 1.0
}
