package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorKin
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore
import kotlin.math.ln

/**
 * THE LAYERS OF A PAIR NO SEARCH KEPT, worked out as the pair is shown the same as a search works them out
 * ([PairSearch.layers]): its two works read alone ([SearchSide.readOf]) — the vectors of their texts, their values,
 * their numbers of size, their authors with every one the dictionary gives as one with them —, their values weighed by
 * how rare the last search found them among every work of the type ([ValueRarities]); a value it did not meet, as rare
 * as a value of one work of them all; of a type never searched, every value alike.
 */
internal object LoosePairs {

    /**
     * The pair of [first] and [second] with its layers by the texts' vectors of [encoder]; null when either has none.
     */
    fun candidate(type: TypeStore, encoder: String, first: ItemKey, second: ItemKey): DuplicateCandidate? =
        sidesOf(type, encoder, first, second)?.let { layersOf(type, first, second, it) }

    /**
     * The two works read alone, each its side; null when either is no work of the type, or has no vector of its title.
     */
    private fun sidesOf(type: TypeStore, encoder: String, first: ItemKey, second: ItemKey): List<ReadSide>? {
        val keys = listOf(first, second)
        val stores = keys.mapNotNull { type.source(it.source) }.takeIf { it.size == keys.size } ?: return null
        val works = keys.zip(stores).flatMap { (k, s) -> s.works.ofItems(s.id, listOf(k.id)).values }
        val ratios = type.sizes.of(works)
        return keys
            .zip(stores)
            .mapNotNull { (k, s) -> SearchSide.readOf(s, encoder, listOf(k.id), ratios) }
            .takeIf { it.size == keys.size }
    }

    /** The layers of the pair of the two works [read]; null when their vectors are not alike — not compared. */
    private fun layersOf(type: TypeStore, first: ItemKey, second: ItemKey, read: List<ReadSide>): DuplicateCandidate? {
        // The values of the two by their places, one place a value whichever work has it
        val places = HashMap<String, Int>()
        val sides = read.map { it.placed(places) }
        val names = arrayOfNulls<String>(places.size).also { out -> places.forEach { (name, at) -> out[at] = name } }
        val known = type.duplicates.rarity.of(places.keys)
        // A value the last search did not meet as rare as a value of one work of them all; of a type never searched,
        // every value alike
        val rarest = type.duplicates.search()?.let { ln(it.works.coerceAtLeast(2).toDouble()).toFloat() } ?: 1f
        val rarity = FloatArray(places.size) { known[names[it]] ?: rarest }
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
                emptyMap(),
            )
        if (!search.compared(0, 1)) return null
        val layers = search.layers(0, 0, 1, 0)
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
}
