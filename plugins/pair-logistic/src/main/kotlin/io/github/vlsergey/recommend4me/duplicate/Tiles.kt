package io.github.vlsergey.recommend4me.duplicate

/**
 * A RUN OF A SITE'S WORKS, from its [from]-th on: their [works] as a site of their own ([SiteWorks]) — the vectors over
 * the site's own memory, the rest the runs of its arrays.
 */
internal class SiteSlice(val from: Int, val works: SiteWorks)

/**
 * THE TILES OF THE SEARCH ON THE CARD (user, 2026-11-17: "split the whole matrix into N × N and make N × N passes"):
 * every site cut into runs of [SLICE] works at most, and of two sites every run of the first against every run of the
 * second. A block of rows against a run, not against the whole other site, takes planes of the run's width: of a
 * million works a row's planes were 74 MB, ten rows a block, and the products read the other site's every vector again
 * for every ten rows; of a run of 65 536, 3.5 MB, a thousand rows a block.
 */
internal object Tiles {
    /** The works of a run at most. */
    const val SLICE = 65_536

    /** The runs of the [site]'s works, of [size] works at most. */
    fun slices(site: SiteWorks, size: Int = SLICE): List<SiteSlice> =
        (0 until site.count step size).map { from ->
            SiteSlice(from, slice(site, from, minOf(site.count, from + size)))
        }

    /** The [site]'s works from [from] to [until] as a site of their own. */
    private fun slice(site: SiteWorks, from: Int, until: Int): SiteWorks =
        SiteWorks(
            site.source,
            site.ids.subList(from, until),
            site.titles.rows(from, until - from),
            site.titleLengths.copyOfRange(from, until),
            site.authorNames.rows(from, until - from),
            site.authorLengths.copyOfRange(from, until),
            site.descriptions.rows(from, until - from),
            site.described.copyOfRange(from, until),
            site.values.copyOfRange(from, until),
            site.weights.copyOfRange(from, until),
            site.facets.copyOfRange(from, until),
            Array(site.sizes.size) { site.sizes[it].copyOfRange(from, until) },
            site.authors.copyOfRange(from, until),
            site.kin.copyOfRange(from, until),
        )
}

/**
 * THE [a]-TH SITE'S RUN [rows] AGAINST THE [b]-TH'S RUN [columns]: the works answered on of every row passed over by
 * their places in the run ([passed]); a run against itself never a work with itself ([self]).
 */
internal class Tile(
    val search: PairSearch,
    val a: Int,
    val b: Int,
    val rows: SiteSlice,
    val columns: SiteSlice,
) {
    val own: SiteWorks = rows.works
    val other: SiteWorks = columns.works
    val self: Boolean = a == b && rows.from == columns.from

    /** Of every row, the works of the run answered on with it, by their places in the run. */
    val passed: List<IntArray> =
        search.answered[a to b].let { answered ->
            val until = columns.from + other.count
            List(own.count) { r ->
                answered
                    ?.get(rows.from + r)
                    ?.filter { it >= columns.from && it < until }
                    ?.map { it - columns.from }
                    ?.toIntArray() ?: IntArray(0)
            }
        }
}
