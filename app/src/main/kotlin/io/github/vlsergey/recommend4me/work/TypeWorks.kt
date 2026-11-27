package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.correction.TypeLinkRepository.Companion.DIFFERENT
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.worklist.WorkCursor
import io.github.vlsergey.recommend4me.worklist.WorkDatum
import java.time.Instant

/**
 * THE NORMALIZED WORKS OF THE ITEMS OF ONE CONTENT TYPE: of its [sources], the works of items and the items of works.
 */
class TypeWorks(private val works: WorkRepository, private val sources: Collection<String>) {

    /** The [datum] of the works of the [items] changed by the user's word [at] ([WorkChanges]). */
    fun changed(items: Collection<ItemKey>, datum: WorkDatum, at: Instant) = works.changes.changed(items, datum, at)

    /** The [datum] of the normalized [works] changed [at] ([WorkChanges]). */
    fun worksChanged(works: Collection<Long>, datum: WorkDatum, at: Instant) =
        this.works.changes.worksChanged(works, datum, at)

    /** The next [limit] works of the type the user's word changed after the [cursor], in the order of their changes. */
    fun after(cursor: WorkCursor?, limit: Int): List<WorkCursor> = works.changes.after(sources, cursor, limit)

    /** The item [key] removed from the library: of no work. */
    fun forget(key: ItemKey) = works.membership.forget(key)

    /** The items of the [works] among the type's sources: work to its items. */
    fun itemsOf(works: Collection<Long>): Map<Long, List<ItemKey>> = this.works.itemsOf(works, sources)

    /** Every two works the user said to be different works, the smaller id first. */
    fun differentWorks(): List<Pair<Long, Long>> =
        works.relations.words().filterValues { it == DIFFERENT }.keys.toList()

    /** The normalized works of the [items]: item to work, of those bound to one. */
    fun worksOf(items: Collection<ItemKey>): Map<ItemKey, Long> =
        items
            .groupBy { it.source }
            .flatMap { (source, keys) ->
                works.ofItems(source, keys.map { it.id }).map { (id, work) -> ItemKey(source, id) to work }
            }
            .toMap()
}
