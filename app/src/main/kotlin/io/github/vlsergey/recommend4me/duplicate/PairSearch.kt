package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorKin
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.source.TypeStore

/**
 * THE SEARCH: every work of every site against every work of every other by every layer at once — the cosines of their
 * titles' and their descriptions' vectors, a block of rows of one site against the whole other ([Blas.gemm]); of the
 * values of the facets the sites share, one layer of them all, each value weighed by how rare it is among every work of
 * the type (the logarithm of the works over those with it: "finished", a value of most works, tells little, a character
 * of a few much), through the works of the other site that have each value; of their size, the mean of the smaller over
 * the larger of every number both have, stated or worked out; whether the dictionary of authors gives them one author,
 * fully or in part — how alike two works are the mean of the layers both have; the most alike of every other site kept
 * of every work, both ways ([found]). Its [sides] read and weighed by [read]; the [families] of facets compared, a bit
 * of a mask each, by their places, and of every value of the type its family's place ([familyOf]).
 */
internal class PairSearch
private constructor(
    private val type: TypeStore,
    val sides: List<SearchSide>,
    val families: List<String>,
    private val familyOf: IntArray,
) {
    /** The works of every site read. */
    val works: Int = sides.sumOf { it.ids.size }

    private val kin = AuthorKin(type.authors.told())

    /** The pairs chosen by the model of the answers as the last fitting left it. */
    private val scorer = DuplicateModel(type.duplicates.search()?.weights).Scorer(families)

    private val bySource = sides.associateBy { it.store.id }

    /**
     * Of every work, the likeliest one work with it of every other site, of the pairs the user did not answer on
     * ([said]), and every pair the user answered on, found or not: what the model learns from.
     */
    fun found(said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>): HashMap<Pair<ItemKey, ItemKey>, DuplicateCandidate> {
        val turnedDown = turnedDown(said)
        val found = HashMap<Pair<ItemKey, ItemKey>, DuplicateCandidate>()
        for (a in sides) for (b in sides) {
            if (a !== b && a.width == b.width)
                SitePair(a, b, turnedDown[a.store.id to b.store.id].orEmpty(), found).compare()
        }
        addAnswered(said, found)
        return found
    }

    /**
     * The pairs the user answered on — one work too: the site may hold a translation of it, a part of it, and one more
     * pair the model is unsure of teaches it — passed over, the next likeliest of that site taken: of every ordered
     * pair of sites, of a work of the first by its place the works of the second by theirs.
     */
    private fun turnedDown(
        said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>
    ): HashMap<Pair<String, String>, HashMap<Int, MutableList<Int>>> {
        val turnedDown = HashMap<Pair<String, String>, HashMap<Int, MutableList<Int>>>()
        said.keys.forEach { (x, y) ->
            val a = bySource[x.source] ?: return@forEach
            val b = bySource[y.source] ?: return@forEach
            val i = a.ids.binarySearch(x.id).takeIf { it >= 0 } ?: return@forEach
            val j = b.ids.binarySearch(y.id).takeIf { it >= 0 } ?: return@forEach
            turnedDown.getOrPut(x.source to y.source) { HashMap() }.getOrPut(i) { ArrayList() } += j
            turnedDown.getOrPut(y.source to x.source) { HashMap() }.getOrPut(j) { ArrayList() } += i
        }
        return turnedDown
    }

    /** Every pair the user answered on that the search did not keep, its layers worked out alone, put in [found]. */
    private fun addAnswered(
        said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>,
        found: HashMap<Pair<ItemKey, ItemKey>, DuplicateCandidate>,
    ) {
        said.keys
            .filter { it !in found }
            .forEach { pair ->
                val a = bySource[pair.first.source] ?: return@forEach
                val b = bySource[pair.second.source] ?: return@forEach
                if (a === b || a.width != b.width) return@forEach
                val i = a.ids.binarySearch(pair.first.id).takeIf { it >= 0 } ?: return@forEach
                val j = b.ids.binarySearch(pair.second.id).takeIf { it >= 0 } ?: return@forEach
                found[pair] = layersOf(pair, a, i, b, j)
            }
    }

    /**
     * The layers of the pair of [a]'s [i]-th work and [b]'s [j]-th, worked out alone: a pair the user answered on that
     * the search did not keep.
     */
    private fun layersOf(
        pair: Pair<ItemKey, ItemKey>,
        a: SearchSide,
        i: Int,
        b: SearchSide,
        j: Int,
    ): DuplicateCandidate {
        val both = a.facets[i] and b.facets[j]
        val facets = facetsAlike(a, i, b, j)
        return DuplicateCandidate(
            pair.first,
            pair.second,
            dot(a.titles, i, b.titles, j, a.width),
            description =
                if (a.described[i] && b.described[j]) dot(a.descriptions, i, b.descriptions, j, a.width) else null,
            size = sizeAlike(a, i, b, j, a.sizes.keys.filter { it in b.sizes }),
            facets = families.indices.filter { both and (1L shl it) != 0L }.associate { families[it] to facets[it] },
            sameAuthor = kin.share(a.authors[i].asList(), b.authors[j].asList()),
        )
    }

    /**
     * Of every facet compared, the cosine of the values of [a]'s [i]-th work with [b]'s [j]-th: their sorted values
     * walked.
     */
    private fun facetsAlike(a: SearchSide, i: Int, b: SearchSide, j: Int): FloatArray {
        val facets = FloatArray(families.size)
        var p = 0
        var q = 0
        while (p < a.values[i].size && q < b.values[j].size) {
            val x = a.values[i][p]
            val y = b.values[j][q]
            when {
                x == y -> facets[familyOf[x]] += a.weights[i][p++] * b.weights[j][q++]
                x < y -> p++
                else -> q++
            }
        }
        return facets
    }

    /** The cosine of the [i]-th unit row of [x] and the [j]-th of [y], rows of [width] numbers. */
    private fun dot(x: Floats, i: Int, y: Floats, j: Int, width: Int): Float {
        val u = FloatArray(width).also { x.take(it, 0, i * width, width) }
        val v = FloatArray(width).also { y.take(it, 0, j * width, width) }
        var sum = 0f
        for (k in u.indices) sum += u[k] * v[k]
        return sum
    }

    /**
     * How alike the sizes of [a]'s [i]-th work and [b]'s [j]-th are: of every number of [keys] both have, the smaller
     * over the larger, their mean; null when they share none.
     */
    private fun sizeAlike(a: SearchSide, i: Int, b: SearchSide, j: Int, keys: List<String>): Float? {
        var sum = 0.0
        var known = 0
        for (key in keys) {
            val x = a.sizes[key]!![i]
            val y = b.sizes[key]!![j]
            if (x > 0 && y > 0) {
                sum += minOf(x, y) / maxOf(x, y)
                known++
            }
        }
        return if (known == 0) null else (sum / known).toFloat()
    }

    /**
     * The works of [a] against every work of [b]: the cosines of a block of [a]'s rows against the whole of [b] at
     * once, and of every row of the block the likeliest one work with it of [b], of those the user did not answer on
     * ([passedOver], by the places of the works), kept in [found]. The buffers of a row made nought again after it.
     */
    private inner class SitePair(
        private val a: SearchSide,
        private val b: SearchSide,
        private val passedOver: Map<Int, List<Int>>,
        private val found: HashMap<Pair<ItemKey, ItemKey>, DuplicateCandidate>,
    ) {
        private val n = b.ids.size
        private val passed = BooleanArray(n)
        private val rows = maxOf(1, minOf(a.ids.size, BLOCK / n))
        private val titles = Floats(rows * n)
        private val descriptions = Floats(rows * n)
        private val title = FloatArray(n)
        private val description = FloatArray(n)

        // Of a work of this site, its values' cosine of every facet with every work of the other, and the works it
        // touched: made nought again after
        private val valued = Array(families.size) { FloatArray(n) }
        private val sizeKeys = a.sizes.keys.filter { it in b.sizes }
        private val touched = IntArray(n)
        private val isTouched = BooleanArray(n)

        // Of a work of this site, the works of the other of one author with it: made false again after
        private val sameAuthor = BooleanArray(n)

        /** Every work of [a] compared, a block of rows at a time. */
        fun compare() {
            var from = 0
            while (from < a.ids.size) {
                val m = minOf(rows, a.ids.size - from)
                multiply(from, m)
                for (r in 0 until m) row(from + r, r)
                from += m
            }
        }

        /**
         * The cosines of the titles and of the descriptions of the [m] works of [a] from [from] with every work of [b].
         */
        private fun multiply(from: Int, m: Int) {
            Blas.gemm(false, true, m, n, a.width, a.titles, from * a.width, a.width, b.titles, 0, b.width, titles, 0, n)
            Blas.gemm(
                false,
                true,
                m,
                n,
                a.width,
                a.descriptions,
                from * a.width,
                a.width,
                b.descriptions,
                0,
                b.width,
                descriptions,
                0,
                n,
            )
        }

        /** The [r]-th row of the block, [a]'s [i]-th work: the likeliest one work with it of [b] kept. */
        private fun row(i: Int, r: Int) {
            titles.take(title, 0, r * n, n)
            descriptions.take(description, 0, r * n, n)
            val touches = touchValues(i)
            val ofOneAuthor = a.authors[i].flatMap { author -> kin.of(author).flatMap { b.withAuthor[it].orEmpty() } }
            ofOneAuthor.forEach { sameAuthor[it] = true }
            val own = passedOver[i]
            own?.forEach { passed[it] = true }
            val best = likeliest(i)
            if (best >= 0) keep(i, best)
            untouch(touches)
            ofOneAuthor.forEach { sameAuthor[it] = false }
            own?.forEach { passed[it] = false }
        }

        /**
         * Of [a]'s [i]-th work, its values' cosine of every facet with every work of [b] that shares a value with it
         * ([valued]), through the works of [b] that have each value; the works touched listed, their count returned.
         */
        private fun touchValues(i: Int): Int {
            var touches = 0
            val ownValues = a.values[i]
            for (k in ownValues.indices) {
                val w = a.weights[i][k]
                val into = valued[familyOf[ownValues[k]]]
                val items = b.withValueItems[ownValues[k]]
                val weights = b.withValueWeights[ownValues[k]]
                for (q in items.indices) {
                    val j = items[q]
                    if (!isTouched[j]) {
                        isTouched[j] = true
                        touched[touches++] = j
                    }
                    into[j] += w * weights[q]
                }
            }
            return touches
        }

        /** The cosines of the values of the first [touches] works touched made nought again. */
        private fun untouch(touches: Int) {
            for (q in 0 until touches) {
                val j = touched[q]
                isTouched[j] = false
                for (f in valued.indices) valued[f][j] = 0f
            }
        }

        /** The likeliest one work with [a]'s [i]-th of [b], of those the user did not answer on; -1 of none. */
        private fun likeliest(i: Int): Int {
            val ownFacets = a.facets[i]
            var best = -1
            var score = Double.NEGATIVE_INFINITY
            for (j in 0 until n) {
                if (passed[j]) continue
                val sized = sizeAlike(a, i, b, j, sizeKeys)
                val s =
                    scorer.score(
                        title[j],
                        description[j],
                        a.described[i] && b.described[j],
                        sized ?: 0f,
                        sized != null,
                        ownFacets and b.facets[j],
                        valued,
                        j,
                        sameAuthor[j],
                    )
                if (s > score) {
                    score = s
                    best = j
                }
            }
            return best
        }

        /**
         * The pair of [a]'s [i]-th work and [b]'s [best] kept with its layers, marked found for the [i]-th: the one it
         * is the likeliest of the other site of.
         */
        private fun keep(i: Int, best: Int) {
            val ownFacets = a.facets[i]
            val sought = ItemKey(a.store.id, a.ids[i])
            val (first, second) = type.links.ordered(sought, ItemKey(b.store.id, b.ids[best]))
            val candidate =
                found.getOrPut(first to second) {
                    DuplicateCandidate(
                        first,
                        second,
                        title[best],
                        description = if (a.described[i] && b.described[best]) description[best] else null,
                        size = sizeAlike(a, i, b, best, sizeKeys),
                        facets =
                            families.indices
                                .filter { (ownFacets and b.facets[best]) and (1L shl it) != 0L }
                                .associate { families[it] to valued[it][best] },
                        sameAuthor = sameAuthor[best],
                    )
                }
            // Found for this work: the one it is the likeliest of the other site of
            candidate.foundFor =
                candidate.foundFor or (if (sought == first) DuplicateCandidate.FIRST else DuplicateCandidate.SECOND)
        }
    }

    companion object {
        /** The cosines held at once, a block of one site's rows against the whole other: memory, not meaning. */
        private const val BLOCK = 16 shl 20

        /**
         * The search of the type by the [encoder]'s vectors: every site read, every value of the type weighed by how
         * rare it is among every work of the type, and its facet given its place.
         */
        fun read(type: TypeStore, encoder: String): PairSearch {
            val places = HashMap<String, Int>()
            val ratios = type.sizes.all()
            val sides = type.sources.mapNotNull { SearchSide.read(it, encoder, places, ratios) }
            val works = sides.sumOf { it.ids.size }
            val having = IntArray(places.size)
            sides.forEach { side -> side.values.forEach { own -> own.forEach { having[it]++ } } }
            val rarity =
                FloatArray(places.size) { t ->
                    if (having[t] == 0) 0f else Math.log(works.toDouble() / having[t]).toFloat()
                }
            // Every value's facet: the name before its value ("characters:wikidata:Q…")
            val names =
                arrayOfNulls<String>(places.size).also { out -> places.forEach { (name, at) -> out[at] = name } }
            val families = names.map { it!!.substringBefore(':') }.distinct().sorted()
            require(families.size < Long.SIZE_BITS) { "${families.size} facets of the type: no bit of a mask each" }
            val familyAt = families.withIndex().associate { it.value to it.index }
            val familyOf = IntArray(places.size) { familyAt.getValue(names[it]!!.substringBefore(':')) }
            sides.forEach { it.weigh(rarity, familyOf) }
            return PairSearch(type, sides, families, familyOf)
        }
    }
}
