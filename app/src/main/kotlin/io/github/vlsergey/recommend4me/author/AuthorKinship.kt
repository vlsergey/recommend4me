package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.duplicate.DuplicateAnswer
import io.github.vlsergey.recommend4me.duplicate.DuplicateCandidate
import io.github.vlsergey.recommend4me.duplicate.PairChances
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters

/**
 * How close two authors are on the evidence of their works, as the offers of authors weigh it ([AuthorOffers]):
 * [matching] pairs of their works the user said are one work or a part of the other — two works of one site made one
 * among them —, [expected] the sum of the chances the rest the search kept are one, whether both are bound to
 * [oneEntry] of a catalogue, and the user's word on them ([told]: true partly one, false told apart).
 */
class AuthorsKin(
    val first: Long,
    val second: Long,
    val matching: Int,
    val expected: Double,
    val oneEntry: Boolean,
    val told: Boolean?,
)

/**
 * THE KINSHIP OF THE AUTHORS OF TWO WORKS, worked out as a pair of works is shown: of every author of one and every
 * author of the other, what their works tell — the same evidence the offers of authors weigh, of these authors alone —
 * the closest two. Shown, never kept: two works are told one by their authors, two authors one by their works — kept,
 * each would go round through the other.
 */
object AuthorKinship {

    /**
     * The closest two of the authors [first] of a work and [second] of another — the user's word partly one first, then
     * one entry, then the most works matching, then the most expected to —; null when either has none, or every author
     * is of both. Of the user's words on pairs of works [said], the chances of the pairs the search kept [chances], the
     * normalized works [clusters].
     */
    fun of(
        type: TypeStore,
        first: Set<Long>,
        second: Set<Long>,
        said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>,
        chances: PairChances?,
        clusters: WorkClusters,
    ): AuthorsKin? {
        val pairs = first.flatMap { a -> second.filter { it != a }.map { b -> a to b } }
        if (pairs.isEmpty()) return null
        val worksOf = worksOf(type, first + second)
        val evidence =
            Evidence(
                type,
                worksOf,
                type.duplicates.touching(worksOf.values.flatten().distinct()),
                said,
                chances,
                clusters,
            )
        return pairs
            .map { (a, b) -> evidence.kin(a, b) }
            .maxWith(
                compareBy<AuthorsKin> { it.told == true }
                    .thenBy { it.oneEntry }
                    .thenBy { it.matching }
                    .thenBy { it.expected }
            )
    }

    /**
     * What tells of the authors, read once for every two of them: their works ([worksOf]), the pairs of them the search
     * kept ([kept]), the user's words on pairs of works ([said]) and on authors, the entries the authors are bound to.
     */
    private class Evidence(
        private val type: TypeStore,
        private val worksOf: Map<Long, List<ItemKey>>,
        private val kept: List<DuplicateCandidate>,
        private val said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>,
        private val chances: PairChances?,
        private val clusters: WorkClusters,
    ) {
        private val told = type.authors.told()
        private val entries = type.authors.entries.all()

        /** How close the authors [a] and [b] are: of their works but those they wrote together, which tell nothing. */
        fun kin(a: Long, b: Long): AuthorsKin {
            val ofA = worksOf[a].orEmpty().toSet()
            val ofB = worksOf[b].orEmpty().toSet()
            val own = ofA - ofB
            val other = ofB - ofA
            val matching = own.sumOf { x -> other.count { y -> matches(x, y) } }
            val expected =
                kept
                    .filter { c -> (c.first in own && c.second in other) || (c.second in own && c.first in other) }
                    .filter { said[it.pair] == null }
                    .sumOf { chances?.same(it) ?: 0.0 }
            val shared = entries[a].orEmpty().any { it in entries[b].orEmpty() }
            return AuthorsKin(a, b, matching, expected, shared, told[AuthorRepository.pair(a, b)])
        }

        /** Whether the user said two works are one or a part of the other: by a word, or made one work of them. */
        private fun matches(x: ItemKey, y: ItemKey): Boolean =
            when (said[type.links.ordered(x, y)]) {
                DuplicateAnswer.SAME,
                DuplicateAnswer.PARTIAL -> true
                null -> y in clusters.members(x)
                else -> false
            }
    }

    /** The works of every one of the [authors], by their accounts on every site. */
    private fun worksOf(type: TypeStore, authors: Set<Long>): Map<Long, List<ItemKey>> {
        val out = HashMap<Long, MutableList<ItemKey>>()
        type.authors.sitesOf(authors).forEach { (author, sites) ->
            sites
                .groupBy { it.source }
                .forEach { (source, refs) ->
                    val s = type.source(source) ?: return@forEach
                    val facet = authorFacetOf(s) ?: return@forEach
                    s.items.itemsWith(facet, refs.map { it.author }).values.flatten().forEach { id ->
                        out.getOrPut(author) { ArrayList() } += ItemKey(source, id)
                    }
                }
        }
        return out
    }
}
