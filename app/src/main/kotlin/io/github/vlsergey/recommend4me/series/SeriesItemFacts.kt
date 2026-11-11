package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.size.ItemSize
import io.github.vlsergey.recommend4me.size.WorkSizes
import io.github.vlsergey.recommend4me.source.TypeStore
import java.time.Instant

/**
 * WHAT THE WORKS OF A SERIES ARE TOLD BY BESIDES THEIR ORDER, of their [items], each source's read in one go: the size
 * of every item that has one — the volume of a site's series is of them too — and when its first chapter was published,
 * of an item a chapter of which is dated.
 */
internal class SeriesItemFacts(type: TypeStore, items: Collection<ItemKey>, sizes: WorkSizes) {
    val sizes = HashMap<ItemKey, ItemSize>()
    val firstPublished = HashMap<ItemKey, Instant>()

    init {
        items
            .groupBy { it.source }
            .forEach { (source, keys) ->
                val s = type.source(source) ?: return@forEach
                val ids = keys.map { it.id }
                sizes.ofItems(type, s, ids, s.items.numbersOf(ids), s.corrections.fieldsOf(ids)).forEach { (id, size) ->
                    this.sizes[ItemKey(source, id)] = size
                }
                s.parts.firstPublished(ids).forEach { (id, at) -> firstPublished[ItemKey(source, id)] = at }
            }
    }
}
