package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.NumberJoin
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.ValueJoin

/**
 * WHAT THE MODEL READS OF A NORMALIZED WORK OF SEVERAL ITEMS, made of what it reads of each of them: every categorical
 * feature any of them has, or of a facet joined otherwise the way it says ([ValueJoin]) — of the status the greatest;
 * of a number or a vector several have — a title, a description, the pictures taken as one — their mean, or of a number
 * joined otherwise the way it says ([NumberJoin]) — of the size the greatest. The sets (the reviews, the screenshots,
 * the windows of the text) are made anew of the members of all the items
 * ([io.github.vlsergey.recommend4me.setvector.SetVectors.ofWork]), where that can be read.
 */
object WorkFeatures {

    /**
     * How the features of the items of a work are joined: a number by its feature's name ([numbers]), the mean of one
     * not there; the values of a facet joined by the one listed first ([firstListed]: the keys in their order, by the
     * facet's id), every value of one not there.
     */
    class Joins(
        val numbers: Map<String, NumberJoin> = emptyMap(),
        val firstListed: Map<String, List<String>> = emptyMap(),
    )

    /** The work of the [items] as one, named by [key]: its latest update, the version of its naming item. */
    fun item(key: ItemKey, items: List<CatalogueItem>, joins: Joins = Joins()): CatalogueItem {
        val own = items.first { it.key == key }
        val numbers = HashMap<String, MutableList<Float>>()
        items.forEach { item ->
            item.numeric.forEach { (name, value) -> numbers.getOrPut(name) { ArrayList() } += value }
        }
        return CatalogueItem(
            key,
            own.version,
            items.maxOf { it.updatedAt },
            firstListed(items.flatMapTo(LinkedHashSet()) { it.categorical }, joins.firstListed),
            numbers.mapValues { (name, values) -> join(joins.numbers[name] ?: NumberJoin.MEAN, values) },
        )
    }

    /** How every feature of the [sources]' items is joined across the items of a work. */
    fun joins(sources: List<Source>): Joins =
        Joins(
            sources
                .flatMap { s -> s.schema.numbers.map { FeatureNames.number(FeatureNames.numberId(s, it)) to it.join } }
                .toMap(),
            sources
                .flatMap { s ->
                    s.schema.facets.mapNotNull { facet ->
                        facet.standard
                            ?.takeIf { it.join == ValueJoin.FIRST_LISTED }
                            ?.values
                            ?.let { values -> FeatureNames.facetId(s, facet) to values.map { it.key } }
                    }
                }
                .toMap(),
        )

    private fun join(how: NumberJoin, values: List<Float>): Float =
        when (how) {
            NumberJoin.MEAN -> values.average().toFloat()
            NumberJoin.MAX -> values.max()
            NumberJoin.AGREEMENT -> (values.min() + values.max()) / 2
        }

    /**
     * The [features] with, of every facet of [orders], only the value listed first among those they have; a value not
     * listed kept.
     */
    private fun firstListed(features: Set<String>, orders: Map<String, List<String>>): Set<String> {
        if (orders.isEmpty()) return features
        fun place(feature: String): Pair<String, Int>? =
            FeatureNames.parseFacet(feature)?.let { (id, key) ->
                orders[id]?.indexOf(key)?.takeIf { it >= 0 }?.let { id to it }
            }
        val first = HashMap<String, Int>()
        features.forEach { feature -> place(feature)?.let { (id, at) -> first.merge(id, at, ::minOf) } }
        return features.filterTo(LinkedHashSet()) { feature ->
            place(feature)?.let { (id, at) -> first[id] == at } ?: true
        }
    }

    /** Of every block the mean of the vectors the [members] have of it. */
    fun vectors(members: List<Map<String, FloatArray>>): Map<String, FloatArray> {
        if (members.size <= 1) return members.firstOrNull().orEmpty()
        val out = HashMap<String, FloatArray>()
        members
            .flatMapTo(HashSet()) { it.keys }
            .forEach { block ->
                val own = members.mapNotNull { it[block] }
                val size = own.first().size
                val same = own.filter { it.size == size }
                out[block] = FloatArray(size) { k -> (same.sumOf { it[k].toDouble() } / same.size).toFloat() }
            }
        return out
    }
}
