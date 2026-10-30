package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * WHAT THE MODEL READS OF A NORMALIZED WORK OF SEVERAL ITEMS, made of what it reads of each of them:
 * every categorical feature any of them has; of a number or a vector several have — a title, a
 * description, the pictures taken as one — their mean. The sets (the reviews, the screenshots, the
 * windows of the text) are made anew of the members of all the items ([io.github.vlsergey.recommend4me.setvector.SetVectors.ofWork]),
 * where that can be read.
 */
object WorkFeatures {

    /** The work of the [items] as one, named by [key]: its latest update, the version of its naming item. */
    fun item(key: ItemKey, items: List<CatalogueItem>): CatalogueItem {
        val own = items.first { it.key == key }
        val numbers = HashMap<String, MutableList<Float>>()
        items.forEach { item -> item.numeric.forEach { (name, value) -> numbers.getOrPut(name) { ArrayList() } += value } }
        return CatalogueItem(
            key, own.version, items.maxOf { it.updatedAt },
            items.flatMapTo(LinkedHashSet()) { it.categorical },
            numbers.mapValues { (_, values) -> values.average().toFloat() },
        )
    }

    /** Of every block the mean of the vectors the [members] have of it. */
    fun vectors(members: List<Map<String, FloatArray>>): Map<String, FloatArray> {
        if (members.size <= 1) return members.firstOrNull().orEmpty()
        val out = HashMap<String, FloatArray>()
        members.flatMapTo(HashSet()) { it.keys }.forEach { block ->
            val own = members.mapNotNull { it[block] }
            val size = own.first().size
            val same = own.filter { it.size == size }
            out[block] = FloatArray(size) { k -> (same.sumOf { it[k].toDouble() } / same.size).toFloat() }
        }
        return out
    }
}
