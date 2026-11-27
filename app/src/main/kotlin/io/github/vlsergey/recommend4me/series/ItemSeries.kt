package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore

/**
 * THE NORMALIZED SERIES OF ITEMS, read by their keys: of their works, the series kept of each
 * ([NormalizedSeriesRepository]).
 */
object ItemSeries {
    /** Of the [items] of the [type], the number of the series of each one's work: item to it, of those of one. */
    fun of(type: TypeStore, items: Collection<ItemKey>): Map<ItemKey, Long> {
        val works = type.works.worksOf(items)
        val series = type.series.ofWorks(works.values)
        return works.mapNotNull { (item, work) -> series[work]?.let { item to it } }.toMap()
    }
}
