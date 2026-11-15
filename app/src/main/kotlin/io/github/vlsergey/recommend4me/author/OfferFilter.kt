package io.github.vlsergey.recommend4me.author

/**
 * Which pairs of authors a list of the offers shows: those of the [author] alone, when given; those of whom one author
 * at least has an account with works on every site of [onSite]; those of whom neither has one on any site of [offSite].
 */
data class OfferFilter(
    val author: Long? = null,
    val onSite: Set<String> = emptySet(),
    val offSite: Set<String> = emptySet(),
) {
    /** Whether the two [authors] are shown, each with the sites they have works on by [sitesOf]. */
    fun lets(authors: Pair<Long, Long>, sitesOf: (Long) -> Set<String>): Boolean {
        if (author != null && authors.first != author && authors.second != author) return false
        if (onSite.isEmpty() && offSite.isEmpty()) return true
        val sites = sitesOf(authors.first) + sitesOf(authors.second)
        return sites.containsAll(onSite) && offSite.none { it in sites }
    }
}
