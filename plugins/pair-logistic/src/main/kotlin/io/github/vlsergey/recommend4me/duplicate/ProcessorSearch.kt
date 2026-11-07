package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats

/**
 * THE SEARCH IN THE MAIN MEMORY, of a launch without a card: every work of a site against every work of another by
 * every layer at once ([Against]), and every model's score of every pair made of them, the likeliest of every model
 * kept.
 */
internal object ProcessorSearch {
    fun search(search: PairSearch, models: List<Logistic.Laid>): List<FoundPair> {
        val out = ArrayList<FoundPair>()
        for (a in search.sites.indices) for (b in search.sites.indices) {
            if (search.compared(a, b)) out += Against(search, a, b, models).pairs()
        }
        return out
    }

    /**
     * Of the [a]-th site's [i]-th work, the works of the [b]-th it is likeliest with by every model ([best], -1: none),
     * each once with the bits of its models.
     */
    fun found(a: Int, i: Int, b: Int, best: IntArray): List<FoundPair> {
        val kinds = LinkedHashMap<Int, Int>()
        best.forEachIndexed { k, j -> if (j >= 0) kinds.merge(j, 1 shl k, Int::or) }
        return kinds.map { (j, bits) -> FoundPair(a, i, b, j, bits) }
    }
}

/**
 * THE [a]-TH SITE'S WORKS AGAINST THE [b]-TH'S IN THE MAIN MEMORY: the cosines of their titles' and their descriptions'
 * vectors, a block of rows of the first against the whole second ([Blas.gemm]); of the values of the facets the sites
 * share, through the works of the second that have each value ([Inverted]); of their size, of every number both have
 * the smaller over the larger, their mean; whether the dictionary of authors gives them one author.
 */
private class Against(
    private val search: PairSearch,
    private val a: Int,
    private val b: Int,
    private val models: List<Logistic.Laid>,
) {
    private val x = search.sites[a]
    private val y = search.sites[b]
    private val n = y.count
    private val width = x.width
    private val other = Inverted(y, search.familyOf.size)
    private val passedOver = search.answered[a to b].orEmpty()
    private val passed = BooleanArray(n)
    private val rows = maxOf(1, minOf(x.count, BLOCK / maxOf(1, n)))
    private val titles = Floats(rows * n)
    private val descriptions = Floats(rows * n)
    private val title = FloatArray(n)
    private val description = FloatArray(n)

    // Of a work of the first site, its values' cosine of every facet with every work of the second, and the works it
    // touched: made nought again after
    private val valued = Array(search.families.size) { FloatArray(n) }
    private val touched = IntArray(n)
    private val isTouched = BooleanArray(n)
    private var touches = 0

    // Of a work of the first site, the works of the second of one author with it: made false again after
    private val sameAuthor = BooleanArray(n)
    private val best = IntArray(models.size)
    private val scores = DoubleArray(models.size)

    fun pairs(): List<FoundPair> {
        val out = ArrayList<FoundPair>()
        var from = 0
        while (from < x.count) {
            val m = minOf(rows, x.count - from)
            Blas.gemm(
                false,
                true,
                m,
                n,
                width,
                x.titles.held,
                from * width,
                width,
                y.titles.held,
                0,
                width,
                titles,
                0,
                n,
            )
            Blas.gemm(
                false,
                true,
                m,
                n,
                width,
                x.descriptions.held,
                from * width,
                width,
                y.descriptions.held,
                0,
                width,
                descriptions,
                0,
                n,
            )
            for (r in 0 until m) out += row(from + r, r)
            from += m
        }
        return out
    }

    /** The [i]-th work, the [r]-th row of its block: of every model, the likeliest work of the second site. */
    private fun row(i: Int, r: Int): List<FoundPair> {
        titles.take(title, 0, r * n, n)
        descriptions.take(description, 0, r * n, n)
        touch(i)
        val ofOneAuthor = x.kin[i].flatMap { author -> other.ofAuthor(author).map { other.authorItems[it] } }
        ofOneAuthor.forEach { sameAuthor[it] = true }
        val own = passedOver[i]
        own?.forEach { passed[it] = true }
        choose(i)
        val found = ProcessorSearch.found(a, i, b, best)
        for (q in 0 until touches) {
            isTouched[touched[q]] = false
            for (f in valued.indices) valued[f][touched[q]] = 0f
        }
        ofOneAuthor.forEach { sameAuthor[it] = false }
        own?.forEach { passed[it] = false }
        return found
    }

    /**
     * Of the [i]-th work's every value, the works of the second site that have it: their cosine of the value's facet
     * grows.
     */
    private fun touch(i: Int) {
        touches = 0
        x.values[i].forEachIndexed { k, v ->
            val w = x.weights[i][k]
            val into = valued[search.familyOf[v]]
            for (q in other.at[v] until other.at[v + 1]) {
                val j = other.items[q]
                if (!isTouched[j]) {
                    isTouched[j] = true
                    touched[touches++] = j
                }
                into[j] += w * other.weights[q]
            }
        }
    }

    /** Of every model, the work of the second site of the highest score with the [i]-th, those passed over left out. */
    private fun choose(i: Int) {
        best.fill(-1)
        scores.fill(Double.NEGATIVE_INFINITY)
        for (j in 0 until n) {
            if (passed[j]) continue
            val sized = size(i, j)
            val both = x.facets[i] and y.facets[j]
            val hasDescription = x.described[i] && y.described[j]
            models.forEachIndexed { k, model ->
                val s =
                    model.score(
                        title[j],
                        description[j],
                        hasDescription,
                        sized ?: 0f,
                        sized != null,
                        both,
                        { f -> valued[f][j] },
                        sameAuthor[j],
                    )
                if (s > scores[k]) {
                    scores[k] = s
                    best[k] = j
                }
            }
        }
    }

    /**
     * How alike the sizes of the two are: of every number both have, the smaller over the larger, their mean; null when
     * they share none.
     */
    private fun size(i: Int, j: Int): Float? {
        var sum = 0.0
        var known = 0
        for (k in search.sizes.indices) {
            val u = x.sizes[k][i]
            val v = y.sizes[k][j]
            if (u > 0 && v > 0) {
                sum += minOf(u, v) / maxOf(u, v)
                known++
            }
        }
        return if (known == 0) null else (sum / known).toFloat()
    }

    private companion object {
        /**
         * The scores held at once, a block of rows of one site against every work of the other: memory, not meaning.
         */
        const val BLOCK = 1 shl 24
    }
}
