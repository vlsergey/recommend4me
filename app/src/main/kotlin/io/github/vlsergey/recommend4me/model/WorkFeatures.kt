package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.NumberJoin
import io.github.vlsergey.recommend4me.source.Source

/**
 * WHAT THE MODEL READS OF A NORMALIZED WORK OF SEVERAL ITEMS, made of what it reads of each of them: every categorical
 * feature any of them has; of a number or a vector several have — a title, a description, the pictures taken as one —
 * their mean, or of a number joined otherwise the way it says ([NumberJoin]). The sets (the reviews, the screenshots,
 * the windows of the text) are made anew of the members of all the items
 * ([io.github.vlsergey.recommend4me.setvector.SetVectors.ofWork]), where that can be read.
 */
object WorkFeatures {

    /**
     * The work of the [items] as one, named by [key]: its latest update, the version of its naming item; a number by
     * its feature's [joins], the mean of one not there.
     */
    fun item(key: ItemKey, items: List<CatalogueItem>, joins: Map<String, NumberJoin> = emptyMap()): CatalogueItem {
        val own = items.first { it.key == key }
        val numbers = HashMap<String, MutableList<Float>>()
        items.forEach { item ->
            item.numeric.forEach { (name, value) -> numbers.getOrPut(name) { ArrayList() } += value }
        }
        return CatalogueItem(
            key,
            own.version,
            items.maxOf { it.updatedAt },
            items.flatMapTo(LinkedHashSet()) { it.categorical },
            numbers.mapValues { (name, values) -> join(joins[name] ?: NumberJoin.MEAN, values) },
        )
    }

    /** How every numeric feature of the [sources]' items is joined across the items of a work, by its name. */
    fun joins(sources: List<Source>): Map<String, NumberJoin> =
        sources
            .flatMap { s -> s.schema.numbers.map { FeatureNames.number(FeatureNames.numberId(s, it)) to it.join } }
            .toMap()

    private fun join(how: NumberJoin, values: List<Float>): Float =
        when (how) {
            NumberJoin.MEAN -> values.average().toFloat()
            NumberJoin.AGREEMENT -> (values.min() + values.max()) / 2
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
