package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorKin
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.worklist.ListIndex
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.IntStream

/**
 * A pair a search found ([candidate]) with its chances by the models the search was led by: of one work ([same]), and
 * of one series ([series]; none without a model of one) — made once, with its layers, as the search made the pair: what
 * it is kept by and kept with, no model fitted after the search scoring it again. Its works by their places in the
 * search: its first the [site]'s [item]-th, its second the [otherSite]'s [other]-th ([PairSearch.sites]). The user's
 * [word] on it, and whether it is of one series by the series ([Said.derived]): told the pair as it is found, of the
 * few answered, not looked up of every pair.
 */
internal class FoundCandidate(
    val candidate: DuplicateCandidate,
    val same: Double,
    val series: Double?,
    val site: Int,
    val item: Int,
    val otherSite: Int,
    val other: Int,
    val word: DuplicateAnswer? = null,
    val derived: Boolean = false,
) {
    /** The pair with the user's [word] on it, [derived] or not. */
    fun said(word: DuplicateAnswer, derived: Boolean) =
        FoundCandidate(candidate, same, series, site, item, otherSite, other, word, derived)
}

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
     * The pairs the [model] keeps by the models of [chances] — of one work, and of one series when there is one —, on
     * the [card] when there is one, and every pair answered on ([said]): each with its chances by those models, made
     * with its layers.
     */
    fun found(
        said: Said,
        model: PairModel,
        chances: PairChances,
        card: Cublas?,
        progress: (Double) -> Unit = {},
    ): HashMap<Pair<ItemKey, ItemKey>, FoundCandidate> {
        // Of every pair in the order kept, the first find of it, and for which of its works it was found: the one it is
        // the likeliest of
        val firsts = LinkedHashMap<Pair<ItemKey, ItemKey>, FoundPair>()
        val foundFor = HashMap<Pair<ItemKey, ItemKey>, Int>()
        model.search(search, listOfNotNull(chances.same, chances.series), card, progress).forEach { f ->
            val sought = key(f.site, f.item)
            val pair = type.links.ordered(sought, key(f.otherSite, f.other))
            firsts.putIfAbsent(pair, f)
            foundFor.merge(
                pair,
                if (sought == pair.first) DuplicateCandidate.FIRST else DuplicateCandidate.SECOND,
                Int::or,
            )
        }
        // The layers of every pair at once — two products of a thousand numbers each, of half a million pairs — and
        // its chances of them
        val pairs = firsts.entries.toList()
        val made = arrayOfNulls<FoundCandidate>(pairs.size)
        IntStream.range(0, pairs.size).parallel().forEach { k ->
            val (pair, f) = pairs[k]
            made[k] = chances.of(pair, f.site, f.item, f.otherSite, f.other)
        }
        val found = HashMap<Pair<ItemKey, ItemKey>, FoundCandidate>(pairs.size * 2)
        pairs.forEachIndexed { k, (pair, _) ->
            found[pair] = made[k]!!.also { it.candidate.foundFor = foundFor.getValue(pair) }
        }
        // Every pair answered on told its word, found or not: none of the others looked up
        said.forEach { (pair, word) ->
            val derived = pair in said.derived
            val kept = found[pair]
            if (kept != null) found[pair] = kept.said(word, derived)
            else placeOf(pair)?.let { p -> found[pair] = chances.of(pair, p.a, p.i, p.b, p.j).said(word, derived) }
        }
        return found
    }

    /**
     * The [pair] of the [a]-th site's [i]-th work and the [b]-th's [j]-th with its chances by these models, of its
     * layers made once.
     */
    private fun PairChances.of(pair: Pair<ItemKey, ItemKey>, a: Int, i: Int, b: Int, j: Int): FoundCandidate {
        val c = candidate(pair.first, pair.second, a, i, b, j)
        val layers = c.layers
        val straight = pair.first.source == sides[a].store.id && pair.first.id == sides[a].ids[i]
        return if (straight) FoundCandidate(c, same.chance(layers), series?.chance(layers), a, i, b, j)
        else FoundCandidate(c, same.chance(layers), series?.chance(layers), b, j, a, i)
    }

    /**
     * The [pairs] in the order of their keys — of the first work, then of the second, each by its site's source, then
     * its id — of the places of their works: counted out by their first works, the sites by their sources ascending and
     * every site's works ascending as read, the few of one first work by their second. 2.6 million pairs compared by
     * the strings of their keys, object after object, took 7 to 11 s (2026-11-22).
     */
    fun inKeyOrder(pairs: List<FoundCandidate>): List<FoundCandidate> {
        val start = IntArray(sides.size)
        var works = 0
        sides.indices
            .sortedBy { sides[it].store.id }
            .forEach { a ->
                start[a] = works
                works += sides[a].ids.size
            }
        val firstOf = IntArray(pairs.size) { start[pairs[it].site] + pairs[it].item }
        val secondOf = IntArray(pairs.size) { start[pairs[it].otherSite] + pairs[it].other }
        val from = IntArray(works + 1)
        firstOf.forEach { from[it + 1]++ }
        for (w in 1..works) from[w] += from[w - 1]
        val next = from.copyOf()
        val order = IntArray(pairs.size)
        firstOf.forEachIndexed { k, w -> order[next[w]++] = k }
        // The pairs of one first work, by the second: a work may be the likeliest of thousands — a title many works
        // bear —, so sorted as numbers, the second's place above the pair's
        val keyed = LongArray(pairs.size) { (secondOf[order[it]].toLong() shl Int.SIZE_BITS) or order[it].toLong() }
        for (w in 0 until works) if (from[w + 1] - from[w] > 1) keyed.sort(from[w], from[w + 1])
        return List(pairs.size) { pairs[keyed[it].toInt()] }
    }

    private fun key(site: Int, item: Int) = ItemKey(sides[site].store.id, sides[site].ids[item])

    /**
     * The pair of the [a]-th site's [i]-th work and the [b]-th's [j]-th, [first] and [second] in the order kept, with
     * its layers.
     */
    private fun candidate(first: ItemKey, second: ItemKey, a: Int, i: Int, b: Int, j: Int): DuplicateCandidate {
        val x = wordsOf(a, i)
        val y = wordsOf(b, j)
        val straight = first.source == sides[a].store.id && first.id == sides[a].ids[i]
        return DuplicateCandidate.of(
            first,
            second,
            search.layers(a, i, b, j),
            words = if (straight) x else y,
            otherWords = if (straight) y else x,
        )
    }

    /** The place of the words among the numbers of size the search compares; -1 of none. */
    private val wordsAt = search.sizes.indexOf(StandardNumbers.WORDS.key)

    /** The words of the [a]-th site's [i]-th work as the search compared them; none of none known. */
    private fun wordsOf(a: Int, i: Int): Float? =
        if (wordsAt < 0) null else search.sites[a].sizes[wordsAt][i].takeIf { it > 0 }?.toFloat()

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
         * over both ways. The share of the works read told to [progress] as every site is read.
         */
        fun read(
            type: TypeStore,
            encoder: String,
            said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>,
            index: ListIndex,
            progress: (Double) -> Unit = {},
        ): TypeSearch {
            val places = HashMap<String, Int>()
            val model = type.sizes.model()
            val counts = type.sources.associate { it.id to it.items.count().toLong() }
            val total = counts.values.sum().coerceAtLeast(1).toDouble()
            val read = AtomicLong()
            // The sites read side by side, each its own database: their values placed after, in the sites' order
            val sides =
                type.sources
                    .parallelStream()
                    .map { s ->
                        SearchSide.read(s, encoder, model, index).also {
                            progress(read.addAndGet(counts.getValue(s.id)) / total)
                        }
                    }
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
            val kin = AuthorKin(type.authors.distinct.told())
            val search =
                PairSearch(
                    sides.map { it.works(rarity, familyOf, sizeKeys, kin) },
                    families,
                    familyOf,
                    sizeKeys,
                    answered(sides, said),
                    Duplicates.LEAST_CHANCE,
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
