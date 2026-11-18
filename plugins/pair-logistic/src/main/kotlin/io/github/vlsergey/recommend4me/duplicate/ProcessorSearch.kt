package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.HalfMatrix

/**
 * THE SEARCH IN THE MAIN MEMORY, of a launch without a card: every work of a site against every work of every site, its
 * own too, by every layer at once ([Against]), and every model's score of every pair made of them: of every work the
 * likeliest of every model kept ([Likeliest]).
 */
internal object ProcessorSearch {
    fun search(search: PairSearch, models: List<Logistic.Laid>, compared: Compared): List<FoundPair> {
        val likeliest = Likeliest(search, models.size)
        for (a in search.sites.indices) for (b in search.sites.indices) {
            if (!search.compared(a, b)) continue
            val between = models.map { it.between(search.sites[a].source, search.sites[b].source) }
            Against(search, a, b, between, likeliest).offer(compared)
        }
        return likeliest.found()
    }
}

/**
 * THE [a]-TH SITE'S WORKS AGAINST THE [b]-TH'S IN THE MAIN MEMORY — of one site, against each other, a work never with
 * itself —, every model's likeliest of every work offered to the [likeliest]: the cosines of their titles' and their
 * descriptions' vectors, a block of rows of the first against the whole second ([Blas.gemm]); of the values of the
 * facets the sites share, through the works of the second that have each value ([Inverted]); of their size, of every
 * number both have the smaller over the larger, their mean; whether the dictionary of authors gives them one author.
 */
private class Against(
    private val search: PairSearch,
    private val a: Int,
    private val b: Int,
    private val models: List<Logistic.Laid>,
    private val likeliest: Likeliest,
) {
    private val x = search.sites[a]
    private val y = search.sites[b]
    private val n = y.count
    private val width = x.width
    private val other = Inverted(y, search.familyOf.size)
    private val passedOver = search.answered[a to b].orEmpty()
    private val passed = BooleanArray(n)
    private val rows = maxOf(1, minOf(x.count, BLOCK / maxOf(1, n)))

    // The cosines of the titles' and the authors' names' trigrams, and of the descriptions — of one encoder's vectors
    private val titles = Products(x.titles, y.titles, rows)
    private val authorNames = Products(x.authorNames, y.authorNames, rows)
    private val descriptions =
        if (search.descriptionsCompared(a, b)) Products(x.descriptions, y.descriptions, rows) else null
    private val title = FloatArray(n)
    private val author = FloatArray(n)
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

    fun offer(compared: Compared) {
        var from = 0
        while (from < x.count) {
            val m = minOf(rows, x.count - from)
            titles.make(from, m)
            authorNames.make(from, m)
            descriptions?.make(from, m)
            for (r in 0 until m) row(from + r, r)
            compared.add(m.toLong() * n)
            from += m
        }
    }

    /** The [i]-th work, the [r]-th row of its block: of every model, the likeliest work of the second site offered. */
    private fun row(i: Int, r: Int) {
        titles.take(r, title)
        authorNames.take(r, author)
        descriptions?.take(r, description)
        touch(i)
        val ofOneAuthor = x.kin[i].flatMap { author -> other.ofAuthor(author).map { other.authorItems[it] } }
        ofOneAuthor.forEach { sameAuthor[it] = true }
        val own = passedOver[i]
        own?.forEach { passed[it] = true }
        choose(i)
        best.forEachIndexed { k, j -> likeliest.offer(a, i, k, scores[k], b, j) }
        for (q in 0 until touches) {
            isTouched[touched[q]] = false
            for (f in valued.indices) valued[f][touched[q]] = 0f
        }
        ofOneAuthor.forEach { sameAuthor[it] = false }
        own?.forEach { passed[it] = false }
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

    /**
     * Of every model, the work of the second site of the highest score with the [i]-th, those passed over left out —
     * and itself, of one site.
     */
    private fun choose(i: Int) {
        best.fill(-1)
        scores.fill(Double.NEGATIVE_INFINITY)
        for (j in 0 until n) {
            if (passed[j] || a == b && j == i) continue
            val sized = size(i, j)
            val both = x.facets[i] and y.facets[j]
            val hasDescription = descriptions != null && x.described[i] && y.described[j]
            val t = Lexical.of(title[j], x.titleLengths[i], y.titleLengths[j])
            val named = x.authorLengths[i] > 0f && y.authorLengths[j] > 0f
            val n = if (named) Lexical.of(author[j], x.authorLengths[i], y.authorLengths[j]) else null
            models.forEachIndexed { k, model ->
                val s =
                    model.score(
                        t,
                        n,
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

/**
 * THE PRODUCTS OF ONE KIND OF ROWS OF THE TWO SITES ON THE PROCESSOR: the second site's rows [other] made single
 * precision whole, once; the first's [own] a block of [rows] at a time ([make]), their products with every row of the
 * second read a row at a time ([take]).
 */
private class Products(private val own: HalfMatrix, other: HalfMatrix, rows: Int) {
    private val width = own.cols
    private val n = other.rows
    private val others = Floats(n * width).also { other.floats(0, n, it) }
    private val block = Floats(rows * width)
    private val made = Floats(rows * n)

    /** The products of the [m] rows of the first site from [from] on. */
    fun make(from: Int, m: Int) {
        own.floats(from, m, block)
        Blas.gemm(false, true, m, n, width, block, 0, width, others, 0, width, made, 0, n)
    }

    /** The [r]-th row of the block made, into [into]. */
    fun take(r: Int, into: FloatArray) = made.take(into, 0, r * n, n)
}
