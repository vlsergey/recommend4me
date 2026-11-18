package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.HalfMatrix

/**
 * THE WORKS OF ONE SITE AS A SEARCH OF PAIRS READS THEM: every work of a title ([ids], ascending) — the unit row of its
 * trigrams' counts ([titles], a row a work, in half precision; [titleLengths] the counts' lengths, nought of a title of
 * none), the same of the names of its authors ([authorNames], [authorLengths]: nought of a work of no author named) —
 * and of the vectors of their descriptions ([descriptions], a row of noughts and [described] false of a work without
 * one; of no column, a site with no vector of a description); the values of the facets the sites share ([values], by
 * their places among every value of the type, ascending) with the weight of each ([weights]: its rarity among every
 * work of the type, a work's values of each facet of unit length) and the facets it has a value of ([facets], a bit a
 * facet by its place among [PairSearch.families]); the numbers of its size ([sizes], by the places of
 * [PairSearch.sizes], a row a number, nought when not known); its normalized authors ([authors]) and with them every
 * author the dictionary of authors gives as one with them, fully or in part ([kin]).
 */
class SiteWorks(
    val source: String,
    val ids: List<String>,
    val titles: HalfMatrix,
    val titleLengths: FloatArray,
    val authorNames: HalfMatrix,
    val authorLengths: FloatArray,
    val descriptions: HalfMatrix,
    val described: BooleanArray,
    val values: Array<IntArray>,
    val weights: Array<FloatArray>,
    val facets: LongArray,
    val sizes: Array<DoubleArray>,
    val authors: Array<LongArray>,
    val kin: Array<LongArray>,
) {
    val count: Int
        get() = ids.size

    /**
     * How long a row of the trigrams of a title or of the authors' names is: two sites compared when theirs are alike.
     */
    val width: Int
        get() = titles.cols

    init {
        require(titles.rows == count && authorNames.rows == count && descriptions.rows == count) {
            "$source: ${titles.rows} titles, ${authorNames.rows} authors, ${descriptions.rows} descriptions " +
                "of $count works"
        }
        require(authorNames.cols == width && titleLengths.size == count && authorLengths.size == count) {
            "$source: the authors' trigrams of ${authorNames.cols} numbers, not $width; not a length of every work"
        }
        require(
            listOf(described.size, values.size, weights.size, facets.size, authors.size, kin.size).all { it == count }
        ) {
            "$source: not a mark, a value list, an author list of every one of $count works"
        }
        require(sizes.all { it.size == count }) { "$source: not a number of size of every one of $count works" }
    }
}

/**
 * WHAT A SEARCH OF PAIRS IS GIVEN: every site's works ([sites]); the facet of every value by its place ([familyOf], a
 * place among [families]); the numbers of size by their keys ([sizes]: the rows of [SiteWorks.sizes]); and of a site's
 * work, the works of another site the user answered on already, passed over ([answered]: by the places in [sites] of
 * the two sites, the work's place to the other's places).
 *
 * [layers] works out the layers of one pair as every search makes them, on the card or not: what the application keeps
 * of a pair found, and of a pair answered on that no search kept.
 */
class PairSearch(
    val sites: List<SiteWorks>,
    val families: List<String>,
    val familyOf: IntArray,
    val sizes: List<String>,
    val answered: Map<Pair<Int, Int>, Map<Int, IntArray>>,
) {
    init {
        require(families.size < Long.SIZE_BITS) { "${families.size} facets: no bit of a mask each" }
        require(sites.all { it.sizes.size == sizes.size }) { "Not a row of every number of size of every site" }
    }

    /**
     * Whether the works of the [a]-th site and of the [b]-th are compared at all: of trigrams alike — a site with
     * itself too, a work never with itself.
     */
    fun compared(a: Int, b: Int): Boolean = sites[a].width == sites[b].width

    /**
     * Whether the descriptions of the [a]-th site's works and of the [b]-th's are compared: of one encoder's vectors.
     */
    fun descriptionsCompared(a: Int, b: Int): Boolean =
        sites[a].descriptions.cols > 0 && sites[a].descriptions.cols == sites[b].descriptions.cols

    /** THE LAYERS OF THE PAIR of the [a]-th site's [i]-th work and the [b]-th site's [j]-th, worked out alone. */
    fun layers(a: Int, i: Int, b: Int, j: Int): PairLayers {
        val x = sites[a]
        val y = sites[b]
        fun dot(u: HalfMatrix, v: HalfMatrix): Float {
            var sum = 0f
            for (k in 0 until u.cols) sum += u[i, k] * v[j, k]
            return sum
        }
        val title = Lexical.of(dot(x.titles, y.titles), x.titleLengths[i], y.titleLengths[j])
        val authored = x.authorLengths[i] > 0f && y.authorLengths[j] > 0f
        val facets = FloatArray(families.size)
        var p = 0
        var q = 0
        while (p < x.values[i].size && q < y.values[j].size) {
            val s = x.values[i][p]
            val t = y.values[j][q]
            when {
                s == t -> facets[familyOf[s]] += x.weights[i][p++] * y.weights[j][q++]
                s < t -> p++
                else -> q++
            }
        }
        val both = x.facets[i] and y.facets[j]
        return PairLayers(
            title = title.alike,
            description =
                if (descriptionsCompared(a, b) && x.described[i] && y.described[j]) dot(x.descriptions, y.descriptions)
                else null,
            size = size(x, i, y, j),
            facets = families.indices.filter { both and (1L shl it) != 0L }.associate { families[it] to facets[it] },
            sameAuthor = y.authors[j].any { it in x.kin[i] },
            site = x.source,
            otherSite = y.source,
            titleWithin = title.within,
            titleHolding = title.holding,
            authors =
                if (authored) Lexical.of(dot(x.authorNames, y.authorNames), x.authorLengths[i], y.authorLengths[j])
                else null,
        )
    }

    /**
     * How alike the sizes of the two are: of every number both have, the smaller over the larger, their mean; null when
     * they share none.
     */
    private fun size(x: SiteWorks, i: Int, y: SiteWorks, j: Int): Float? {
        var sum = 0.0
        var known = 0
        for (k in sizes.indices) {
            val u = x.sizes[k][i]
            val v = y.sizes[k][j]
            if (u > 0 && v > 0) {
                sum += minOf(u, v) / maxOf(u, v)
                known++
            }
        }
        return if (known == 0) null else (sum / known).toFloat()
    }
}
