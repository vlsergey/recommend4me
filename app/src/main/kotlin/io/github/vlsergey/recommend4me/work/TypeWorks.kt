package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * THE NORMALIZED WORKS OF THE ITEMS OF ONE CONTENT TYPE: of its [sources], the works of items and the items of works.
 */
class TypeWorks(private val works: WorkRepository, private val sources: Collection<String>) {

    /** The items of the [works] among the type's sources: work to its items. */
    fun itemsOf(works: Collection<Long>): Map<Long, List<ItemKey>> = this.works.itemsOf(works, sources)

    /** The normalized works of the [items]: item to work, of those bound to one. */
    fun worksOf(items: Collection<ItemKey>): Map<ItemKey, Long> =
        items
            .groupBy { it.source }
            .flatMap { (source, keys) ->
                works.ofItems(source, keys.map { it.id }).map { (id, work) -> ItemKey(source, id) to work }
            }
            .toMap()
}
