package io.github.vlsergey.recommend4me.duplicate

/**
 * EVERY WORK OF EVERY SITE OF A SEARCH IN ONE NUMBERING ([GraphSearch]): the [site]-th site's [i]-th work is the
 * [start] of the site plus [i]. Of every work, as one list after another: its values ascending ([values], their
 * [valueWeights]), its authors and their kin as numbers of their own, ascending ([authors], [kin]), the works the user
 * answered on with it, by their numbers ([passed]); and the sites' rows — the titles', the authors' names' and the
 * descriptions' trigrams of one [width], the descriptions of one encoder's [described] numbers and the titles' vectors
 * of [titled] (of a site of another, none) — put one site after another on the card ([AllOnCard]). Null of sites whose
 * trigrams are not of one width: such a search is every pair's.
 */
internal class AllWorks private constructor(val search: PairSearch) {
    val sites = search.sites
    val start = IntArray(sites.size + 1).also { s -> sites.forEachIndexed { a, x -> s[a + 1] = s[a] + x.count } }
    val count = start[sites.size]
    val width = sites.first().width

    /** The numbers of a description of the most works: a site of another encoder's has none here. */
    val described =
        sites
            .filter { it.descriptions.cols > 0 }
            .groupBy { it.descriptions.cols }
            .maxByOrNull { (_, of) -> of.sumOf { it.count } }
            ?.key ?: 0

    /** The numbers of a title's vector of the most works: a site of another encoder's has none here. */
    val titled =
        sites
            .filter { it.titleVectors.cols > 0 }
            .groupBy { it.titleVectors.cols }
            .maxByOrNull { (_, of) -> of.sumOf { it.count } }
            ?.key ?: 0

    /** Whether the titles' vectors of two sites are compared, by [site] × sites + [site]. */
    val comparedTitles =
        ByteArray(sites.size * sites.size) { k ->
            if (search.titleVectorsCompared(k / sites.size, k % sites.size)) 1 else 0
        }

    val siteOf = IntArray(count).also { s -> sites.indices.forEach { a -> s.fill(a, start[a], start[a + 1]) } }

    /** Whether the descriptions of two sites are compared, by [site] × sites + [site]. */
    val comparedDescriptions =
        ByteArray(sites.size * sites.size) { k ->
            if (search.descriptionsCompared(k / sites.size, k % sites.size)) 1 else 0
        }

    val values: Lists = Lists.of(sites.flatMap { it.values.asList() })
    val valueWeights = FloatArray(values.entries.size).also { w -> fill(w) { it.weights.asList() } }

    private val authorNumber = HashMap<Long, Int>()

    private fun numbered(ids: LongArray): IntArray =
        ids.distinct().map { authorNumber.getOrPut(it) { authorNumber.size } }.sorted().toIntArray()

    val authors: Lists = Lists.of(sites.flatMap { s -> s.authors.map(::numbered) })
    val kin: Lists = Lists.of(sites.flatMap { s -> s.kin.map(::numbered) })

    val passed: Lists =
        Lists.of(
            sites.indices.flatMap { a ->
                (0 until sites[a].count).map { i ->
                    sites.indices
                        .flatMap { b -> search.answered[a to b]?.get(i)?.map { start[b] + it }.orEmpty() }
                        .toIntArray()
                }
            }
        )

    /** Every work's numbers of size, a row a number of size. */
    val sizes =
        FloatArray(search.sizes.size * count).also { out ->
            sites.forEachIndexed { a, s ->
                s.sizes.forEachIndexed { k, row ->
                    row.forEachIndexed { i, v -> out[k * count + start[a] + i] = v.toFloat() }
                }
            }
        }

    private fun fill(into: FloatArray, of: (SiteWorks) -> List<FloatArray>) {
        var at = 0
        sites.forEach { s ->
            of(s).forEach { own ->
                own.copyInto(into, at)
                at += own.size
            }
        }
    }

    companion object {
        /** The works of the [search] in one numbering; null when their trigrams are not of one width. */
        fun of(search: PairSearch): AllWorks? =
            if (search.sites.isEmpty() || search.sites.any { it.width != search.sites.first().width }) null
            else AllWorks(search)
    }
}
