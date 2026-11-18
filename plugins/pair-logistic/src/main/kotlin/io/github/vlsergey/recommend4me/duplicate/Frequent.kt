package io.github.vlsergey.recommend4me.duplicate

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Frequent::class.java)

/**
 * THE VALUES MANY OF A SITE'S WORKS HAVE, of the works as they are at every search: a value of [SHARE] of the works or
 * more. How a pair's shared values are summed on the card is chosen of it: of a frequent value, the other work's short
 * list of them looked up in the row's — a value nearly every pair shares (a language, a rating) walked through its
 * works would be an addition of every pair; of a rare one, its works walked, a few of every row. Told to the log: how
 * many values of every facet are frequent, and what either way costs of every two sites.
 */
internal object Frequent {
    /** A value of this share of a site's works or more is frequent. */
    private const val SHARE = 1.0 / 16

    /**
     * The frequent values among the works of the [site], numbered: of [MOST] at most, the commonest — the kernel holds
     * a row's weight of every one of them.
     */
    fun of(site: SiteWorks, search: PairSearch): FrequentValues {
        val counts = counts(site, search.familyOf.size)
        val chosen =
            counts.indices
                .filter { counts[it] > 0 && counts[it] >= site.count * SHARE }
                .sortedByDescending { counts[it] }
                .take(MOST)
        val index = IntArray(counts.size) { -1 }.also { a -> chosen.forEachIndexed { k, v -> a[v] = k } }
        val frequent = BooleanArray(counts.size) { index[it] >= 0 }
        val byFacet =
            frequent.indices.filter { frequent[it] }.groupingBy { search.families[search.familyOf[it]] }.eachCount()
        log.info(
            "{}: of {} works, {} values frequent (of {} % of the works or more) {}, {} rare",
            site.source,
            site.count,
            byFacet.values.sum(),
            "%.1f".format(SHARE * PER_CENT),
            byFacet,
            counts.count { it > 0 } - byFacet.values.sum(),
        )
        return FrequentValues(index, IntArray(chosen.size) { search.familyOf[chosen[it]] })
    }

    /**
     * Of the [a]-th site's works against the [b]-th's, what the values they share cost, told to the log: the works the
     * rare values of the first walk through (additions on the card), and the frequent values of the second's works
     * looked up of every pair.
     */
    fun tell(search: PairSearch, a: Int, b: Int, frequent: FrequentValues) {
        val x = counts(search.sites[a], search.familyOf.size)
        val y = counts(search.sites[b], search.familyOf.size)
        var walked = 0L
        var looked = 0L
        for (v in x.indices) {
            if (frequent.index[v] >= 0) looked += y[v].toLong() * search.sites[a].count
            else walked += x[v].toLong() * y[v]
        }
        log.info(
            "{} against {}: of their values shared, {} additions of the rare, {} look-ups of the frequent, of {} pairs",
            search.sites[a].source,
            search.sites[b].source,
            walked,
            looked,
            search.sites[a].count.toLong() * search.sites[b].count,
        )
    }

    private fun counts(site: SiteWorks, values: Int): IntArray =
        IntArray(values).also { c -> site.values.forEach { own -> own.forEach { c[it]++ } } }

    private const val PER_CENT = 100

    /** The frequent values of a site at most (`FREQUENT` of `search.cu`). */
    const val MOST = 512
}

/**
 * The frequent values of a site: of every value its number among them ([index], -1 of a rare one), and their facets.
 */
internal class FrequentValues(val index: IntArray, val family: IntArray)
