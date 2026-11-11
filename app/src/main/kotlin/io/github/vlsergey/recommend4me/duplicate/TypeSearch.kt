package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorKin
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.source.TypeStore
import java.util.stream.IntStream

/**
 * THE SEARCH OF A TYPE'S PAIRS: every site read ([SearchSide]) — every value of the type weighed by how rare it is
 * among every work of the type (the logarithm of the works over those with it: "finished", a value of most works, tells
 * little, a character of a few much) — and handed to the model of pairs ([PairModel.search]) with the pairs the user
 * answered on, passed over: of every work, the likeliest of any site — its own too, never itself — by every model of
 * the kinds of pairs, both ways: of one work and one series, a pair or two a work. The pairs kept with their layers
 * ([PairSearch.layers]), marked found for the work they are the likeliest of ([found]); every pair the user answered on
 * with them, found or not: what the models learn from.
 */
internal class TypeSearch
private constructor(
    private val type: TypeStore,
    val sides: List<SearchSide>,
    val search: PairSearch,
    /** How rare every value is among every work of the type, by its name: kept for the pairs no search keeps. */
    val rarity: Map<String, Float>,
) {
    /** The works of every site read. */
    val works: Int = sides.sumOf { it.ids.size }

    private val bySource = sides.withIndex().associate { (k, side) -> side.store.id to k }

    /**
     * The pairs the [model] keeps by every model of [models], on the [card] when there is one, and every pair answered
     * on ([said]).
     */
    fun found(
        said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>,
        model: PairModel,
        models: List<FittedPairModel>,
        card: Cublas?,
    ): HashMap<Pair<ItemKey, ItemKey>, DuplicateCandidate> {
        // Of every pair in the order kept, the first find of it, and for which of its works it was found: the one it is
        // the likeliest of
        val firsts = LinkedHashMap<Pair<ItemKey, ItemKey>, FoundPair>()
        val foundFor = HashMap<Pair<ItemKey, ItemKey>, Int>()
        model.search(search, models, card).forEach { f ->
            val sought = key(f.site, f.item)
            val pair = type.links.ordered(sought, key(f.otherSite, f.other))
            firsts.putIfAbsent(pair, f)
            foundFor.merge(
                pair,
                if (sought == pair.first) DuplicateCandidate.FIRST else DuplicateCandidate.SECOND,
                Int::or,
            )
        }
        // The layers of every pair at once: two products of a thousand numbers each, of half a million pairs
        val pairs = firsts.entries.toList()
        val made = arrayOfNulls<DuplicateCandidate>(pairs.size)
        IntStream.range(0, pairs.size).parallel().forEach { k ->
            val (pair, f) = pairs[k]
            made[k] = candidate(pair.first, pair.second, f.site, f.item, f.otherSite, f.other)
        }
        val found = HashMap<Pair<ItemKey, ItemKey>, DuplicateCandidate>(pairs.size * 2)
        pairs.forEachIndexed { k, (pair, _) ->
            found[pair] = made[k]!!.also { it.foundFor = foundFor.getValue(pair) }
        }
        said.keys
            .filter { it !in found }
            .forEach { pair ->
                placeOf(pair)?.let { p -> found[pair] = candidate(pair.first, pair.second, p.a, p.i, p.b, p.j) }
            }
        return found
    }

    private fun key(site: Int, item: Int) = ItemKey(sides[site].store.id, sides[site].ids[item])

    /**
     * The pair of the [a]-th site's [i]-th work and the [b]-th's [j]-th, [first] and [second] in the order kept, with
     * its layers.
     */
    private fun candidate(first: ItemKey, second: ItemKey, a: Int, i: Int, b: Int, j: Int): DuplicateCandidate {
        val layers = search.layers(a, i, b, j)
        return DuplicateCandidate(
            first,
            second,
            layers.title,
            layers.description,
            layers.size,
            layers.facets,
            sameAuthor = layers.sameAuthor,
        )
    }

    /**
     * Of a [pair], its sites' and works' places, its first first; null when either is not read, or the two are not
     * compared.
     */
    private fun placeOf(pair: Pair<ItemKey, ItemKey>): Places? {
        val a = bySource[pair.first.source]
        val b = bySource[pair.second.source]
        if (a == null || b == null) return null
        val i = sides[a].ids.binarySearch(pair.first.id)
        val j = sides[b].ids.binarySearch(pair.second.id)
        return Places(a, i, b, j).takeIf { i >= 0 && j >= 0 && search.compared(a, b) }
    }

    /** The places of a pair: its first work's site and place, its second's. */
    private class Places(val a: Int, val i: Int, val b: Int, val j: Int)

    companion object {
        /**
         * The search of the [type] by the [encoder]'s vectors: every site read, every value of the type weighed by how
         * rare it is among every work of the type, its facet given its place; the pairs answered on ([said]) passed
         * over both ways.
         */
        fun read(type: TypeStore, encoder: String, said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>): TypeSearch {
            val places = HashMap<String, Int>()
            val ratios = type.sizes.all()
            // The sites read side by side, each its own database: their values placed after, in the sites' order
            val sides =
                type.sources
                    .parallelStream()
                    .map { SearchSide.read(it, encoder, ratios) }
                    .toList()
                    .mapNotNull { it?.placed(places) }
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
            val familyAt = families.withIndex().associate { it.value to it.index }
            val familyOf = IntArray(places.size) { familyAt.getValue(names[it]!!.substringBefore(':')) }
            val sizeKeys = sides.flatMap { it.sizes.keys }.distinct().sorted()
            val kin = AuthorKin(type.authors.told())
            val search =
                PairSearch(
                    sides.map { it.works(rarity, familyOf, sizeKeys, kin) },
                    families,
                    familyOf,
                    sizeKeys,
                    answered(sides, said),
                )
            return TypeSearch(type, sides, search, names.indices.associate { names[it]!! to rarity[it] })
        }

        /**
         * The pairs answered on ([said]): of every ordered pair of sites, of a work of the first by its place the works
         * of the second by theirs.
         */
        private fun answered(
            sides: List<SearchSide>,
            said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>,
        ): Map<Pair<Int, Int>, Map<Int, IntArray>> {
            val bySource = sides.withIndex().associate { (k, side) -> side.store.id to k }
            val out = HashMap<Pair<Int, Int>, HashMap<Int, MutableList<Int>>>()
            said.keys.forEach { (x, y) ->
                val a = bySource[x.source] ?: return@forEach
                val b = bySource[y.source] ?: return@forEach
                val i = sides[a].ids.binarySearch(x.id).takeIf { it >= 0 } ?: return@forEach
                val j = sides[b].ids.binarySearch(y.id).takeIf { it >= 0 } ?: return@forEach
                out.getOrPut(a to b) { HashMap() }.getOrPut(i) { ArrayList() } += j
                out.getOrPut(b to a) { HashMap() }.getOrPut(j) { ArrayList() } += i
            }
            return out.mapValues { (_, byWork) -> byWork.mapValues { it.value.toIntArray() } }
        }
    }
}
