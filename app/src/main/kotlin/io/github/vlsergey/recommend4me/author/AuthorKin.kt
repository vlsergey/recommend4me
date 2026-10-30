package io.github.vlsergey.recommend4me.author

/**
 * WHICH NORMALIZED AUTHORS COUNT AS ONE FOR TELLING TWO WORKS OF ONE AUTHOR: an author is of their own
 * kin, and so is one the user said is partly them. Read for the operation.
 */
class AuthorKin(told: Map<Pair<Long, Long>, Boolean>) {
    private val partly = HashMap<Long, MutableSet<Long>>().also { out ->
        told.filterValues { it }.keys.forEach { (a, b) ->
            out.getOrPut(a) { HashSet() } += b
            out.getOrPut(b) { HashSet() } += a
        }
    }

    /** The authors counted as [author]: themselves first. */
    fun of(author: Long): Set<Long> = setOf(author) + partly[author].orEmpty()

    /** Whether the two lists of authors have one in common, fully or in part: of several writing together, one is enough. */
    fun share(first: Collection<Long>, second: Collection<Long>): Boolean = first.any { a -> of(a).any { it in second } }
}
