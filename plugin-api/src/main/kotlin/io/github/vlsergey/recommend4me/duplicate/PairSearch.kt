package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.ByteMatrix
import io.github.vlsergey.recommend4me.matrix.Rows
import io.github.vlsergey.recommend4me.matrix.SparseRows

/**
 * THE WORKS OF ONE SITE AS A SEARCH OF PAIRS READS THEM: every work of a title ([ids], ascending) — the unit row of its
 * trigrams' counts ([titles], a row a work, in half precision; [titleLengths] the counts' lengths, nought of a title of
 * none), the same of the names of its authors ([authorNames], [authorLengths]: nought of a work of no author named) —
 * and of the vectors of their descriptions ([descriptions], a row of noughts and [described] false of a work without
 * one; of no column, a site with no vector of a description), the same of the vectors of their titles ([titleVectors],
 * [titled]), the unit rows of the trigrams of the other texts ([letters] and [letterLengths], by [LetteredText]: nought
 * of a work of none). The rows of any kind ([Rows]): the application keeps those of the short texts sparse, the vectors
 * and the trigrams of the descriptions a byte a number (owner, 2026-11-20: the search ran out of memory). The values of
 * the facets the sites share ([values], by their places among every value of the type, ascending) with the weight of
 * each ([weights]: its rarity among every work of the type, a work's values of each facet of unit length) and the
 * facets it has a value of ([facets], a bit a facet by its place among [PairSearch.families]); the numbers of its size
 * ([sizes], by the places of [PairSearch.sizes], a row a number, nought when not known); its normalized authors
 * ([authors]) and with them every author the dictionary of authors gives as one with them, fully or in part ([kin]).
 */
class SiteWorks(
    val source: String,
    val ids: List<String>,
    val titles: Rows,
    val titleLengths: FloatArray,
    val authorNames: Rows,
    val authorLengths: FloatArray,
    val descriptions: Rows,
    val described: BooleanArray,
    val values: Array<IntArray>,
    val weights: Array<FloatArray>,
    val facets: LongArray,
    val sizes: Array<DoubleArray>,
    val authors: Array<LongArray>,
    val kin: Array<LongArray>,
    val titleVectors: Rows = ByteMatrix(ids.size, 0),
    val titled: BooleanArray = BooleanArray(ids.size),
    val letters: List<Rows> = LetteredText.entries.map { SparseRows.of(ids.size, titles.cols) { null } },
    val letterLengths: List<FloatArray> = LetteredText.entries.map { FloatArray(ids.size) },
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
        require(
            titleVectors.rows == count &&
                titled.size == count &&
                letters.size == LETTERED &&
                letters.all { it.rows == count && it.cols == width } &&
                letterLengths.size == LETTERED &&
                letterLengths.all { it.size == count }
        ) {
            "$source: not a title's vector, the trigrams of every text of every one of $count works"
        }
    }
}

/** The texts compared letter by letter besides the title and the authors' names ([LetteredText]). */
val LETTERED = LetteredText.entries.size

/**
 * WHAT A SEARCH OF PAIRS IS GIVEN: every site's works ([sites]); the facet of every value by its place ([familyOf], a
 * place among [families]); the numbers of size by their keys ([sizes]: the rows of [SiteWorks.sizes]); and of a site's
 * work, the works of another site the user answered on already, passed over ([answered]: by the places in [sites] of
 * the two sites, the work's place to the other's places). [least]: the least chance of a pair the application keeps — a
 * pair no model can give so much is not kept whatever the search finds, so a search may leave it uncompared.
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
    val least: Double = 0.0,
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

    /**
     * Whether the vectors of the titles of the [a]-th site's works and of the [b]-th's are compared: of one encoder's.
     */
    fun titleVectorsCompared(a: Int, b: Int): Boolean =
        sites[a].titleVectors.cols > 0 && sites[a].titleVectors.cols == sites[b].titleVectors.cols

    /** THE LAYERS OF THE PAIR of the [a]-th site's [i]-th work and the [b]-th site's [j]-th, worked out alone. */
    fun layers(a: Int, i: Int, b: Int, j: Int): PairLayers {
        val x = sites[a]
        val y = sites[b]
        fun dot(u: Rows, v: Rows): Float = u.dot(i, v, j)
        val title = Lexical.of(dot(x.titles, y.titles), x.titleLengths[i], y.titleLengths[j])
        val authored = x.authorLengths[i] > 0f && y.authorLengths[j] > 0f
        return PairLayers(
            title = title.alike,
            description =
                if (descriptionsCompared(a, b) && x.described[i] && y.described[j]) dot(x.descriptions, y.descriptions)
                else null,
            size = size(x, i, y, j),
            facets = facets(x, i, y, j),
            sameAuthor = y.authors[j].any { it in x.authors[i] },
            site = x.source,
            otherSite = y.source,
            titleWithin = title.within,
            titleHolding = title.holding,
            authors =
                if (authored) Lexical.of(dot(x.authorNames, y.authorNames), x.authorLengths[i], y.authorLengths[j])
                else null,
            titleVector =
                if (titleVectorsCompared(a, b) && x.titled[i] && y.titled[j]) dot(x.titleVectors, y.titleVectors)
                else null,
            letters =
                LetteredText.entries
                    .filter { x.letterLengths[it.ordinal][i] > 0f && y.letterLengths[it.ordinal][j] > 0f }
                    .associateWith { dot(x.letters[it.ordinal], y.letters[it.ordinal]) },
            partlySameAuthor = y.authors[j].none { it in x.authors[i] } && y.authors[j].any { it in x.kin[i] },
        )
    }

    /**
     * Of every facet both have, by its name, how alike their values are: of every value both have, the product of its
     * weights, summed.
     */
    private fun facets(x: SiteWorks, i: Int, y: SiteWorks, j: Int): Map<String, Float> {
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
        return families.indices.filter { both and (1L shl it) != 0L }.associate { families[it] to facets[it] }
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
