package io.github.vlsergey.recommend4me.universe

/**
 * How a way of making the vectors of the sites' fandoms found the universes: the share found first, the mean 1/rank.
 */
class FandomFound(val first: Double, val reciprocal: Double)

/**
 * HOW WELL A WAY OF MAKING THE VECTORS OF THE SITES' FANDOMS FINDS THEIR UNIVERSES, as the user made them: of every
 * universe of two sites' fandoms and more, its first fandom asked for, every other fandom of every universe put in the
 * order of its likeness to it — the best cosine of a vector of each — and where the first of its own universe stands.
 * What tells the ways apart (user, 2026-11-14): a site's description of its fandom a synonym of the name, or added to
 * it, or none.
 */
internal object FandomRetrieval {

    /**
     * The finding of the [universes] — each its sites' fandoms — by the [vectors] of the fandoms; none of none asked.
     */
    fun of(universes: Map<Long, List<SiteFandomRef>>, vectors: Map<SiteFandomRef, List<FloatArray>>): FandomFound? {
        val refs = vectors.keys.sortedBy { it.toString() }
        val owner = HashMap<SiteFandomRef, Long>()
        universes.forEach { (u, own) -> own.forEach { owner[it] = u } }
        val rows = refs.flatMap { ref -> vectors.getValue(ref).map { ref to it } }
        val asked = universes.mapNotNull { (u, own) ->
            own.filter { it in vectors }.sortedBy { it.toString() }.takeIf { it.size >= 2 }?.let { u to it.first() }
        }
        if (asked.isEmpty()) return null
        var first = 0.0
        var reciprocal = 0.0
        asked.forEach { (u, query) ->
            val rank = rankOf(query, vectors.getValue(query), rows) { owner[it] == u }
            if (rank == 1) first++
            if (rank > 0) reciprocal += 1.0 / rank
        }
        return FandomFound(first / asked.size, reciprocal / asked.size)
    }

    /**
     * Where the first fandom of the [rows] — each a fandom and a vector of it — that is [own] stands among them all by
     * the best cosine of a vector of each to one of the [query]'s [vectors]; 0 of none.
     */
    private fun rankOf(
        query: SiteFandomRef,
        vectors: List<FloatArray>,
        rows: List<Pair<SiteFandomRef, FloatArray>>,
        own: (SiteFandomRef) -> Boolean,
    ): Int {
        val best = HashMap<SiteFandomRef, Double>()
        rows.forEach { (ref, v) ->
            if (ref == query) return@forEach
            val likeness = vectors.maxOf { q -> q.indices.sumOf { (q[it] * v[it]).toDouble() } }
            if (likeness > (best[ref] ?: Double.NEGATIVE_INFINITY)) best[ref] = likeness
        }
        return best.entries.sortedByDescending { it.value }.indexOfFirst { own(it.key) } + 1
    }

    /** Of a site's fandom, its description added to its name: their sum of unit length; the name alone of none. */
    fun added(v: FandomVectorsOf): FloatArray {
        val d = v.description ?: return v.name
        val sum = FloatArray(v.name.size) { v.name[it] + d[it] }
        val n = kotlin.math.sqrt(sum.sumOf { (it * it).toDouble() })
        return FloatArray(sum.size) { (sum[it] / n).toFloat() }
    }
}
