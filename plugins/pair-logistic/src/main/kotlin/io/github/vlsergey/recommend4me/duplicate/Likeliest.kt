package io.github.vlsergey.recommend4me.duplicate

/**
 * OF EVERY WORK OF EVERY SITE, THE LIKELIEST WORK OF ANY SITE BY EVERY MODEL — of its own site too, never itself: of
 * every site against every other, and against itself, the likeliest of each model offered ([offer]), the highest kept,
 * of equal scores the first offered. What the search keeps: of every work a pair a model, one pair once with the bits
 * of every model it is the likeliest of ([found]).
 */
internal class Likeliest(private val search: PairSearch, private val models: Int) {
    private val score = search.sites.map { s -> DoubleArray(s.count * models) { Double.NEGATIVE_INFINITY } }
    private val site = search.sites.map { s -> IntArray(s.count * models) { -1 } }
    private val work = search.sites.map { s -> IntArray(s.count * models) { -1 } }

    /** Of the [a]-th site's [i]-th work, by the [k]-th model, the [b]-th site's [j]-th of the score [s]; -1: none. */
    fun offer(a: Int, i: Int, k: Int, s: Double, b: Int, j: Int) {
        val at = i * models + k
        if (j >= 0 && s > score[a][at]) {
            score[a][at] = s
            site[a][at] = b
            work[a][at] = j
        }
    }

    /** The pairs kept: of every work, the likeliest of every model, a pair once with the bits of its models. */
    fun found(): List<FoundPair> {
        val out = ArrayList<FoundPair>()
        search.sites.forEachIndexed { a, s ->
            for (i in 0 until s.count) {
                val kinds = LinkedHashMap<Pair<Int, Int>, Int>()
                for (k in 0 until models) {
                    val at = i * models + k
                    if (work[a][at] >= 0) kinds.merge(site[a][at] to work[a][at], 1 shl k, Int::or)
                }
                kinds.forEach { (place, bits) -> out += FoundPair(a, i, place.first, place.second, bits) }
            }
        }
        return out
    }
}
