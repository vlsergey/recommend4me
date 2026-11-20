package io.github.vlsergey.recommend4me.author

/**
 * Which pairs of authors a list of the offers shows: those of the [author] alone, when given; those of whom one author
 * at least has an account with works on every site of [onSite]; those of whom neither has one on any site of [offSite]
 * ([AuthorCandidateRepository.page]).
 */
data class OfferFilter(
    val author: Long? = null,
    val onSite: Set<String> = emptySet(),
    val offSite: Set<String> = emptySet(),
)
