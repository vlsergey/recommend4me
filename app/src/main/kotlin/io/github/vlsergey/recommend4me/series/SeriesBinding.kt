package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.SiteItemWriteEvent
import io.github.vlsergey.recommend4me.manner.MannerGroup
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkGroupChangeEvent
import io.github.vlsergey.recommend4me.worklist.WorkDatum
import java.time.Instant
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * THE NORMALIZED SERIES MADE ANEW AROUND THE WORKS THEY CHANGE OF ([NormalizedSeriesRepository]): of works whose items'
 * series of the sites were written, of works merged or taken apart, of works put in a series of the user's or out of
 * it, of a work removed — their series now and those they were of read by keys, joined as [SeriesJoin] joins them, and
 * written: of a join, the number of the series that kept the most of its works, a series new numbered anew; a series
 * none of whose works is left taken away. The list of a series' works changed is dated (`normalized_series.works_at`);
 * a work moved to another series — its series a feature of the models — dated so ([WorkDatum.GROUPS]). One at a time:
 * two at once would number one series twice.
 */
@Component
class SeriesBinding(private val stores: Stores) {

    /** The series of the [works] of the [type] and of the series they were of made anew, [now]. */
    @Synchronized
    fun refresh(type: TypeStore, works: Collection<Long>, now: Instant = Instant.now()) {
        if (works.isEmpty()) return
        val repository = type.series
        // Around the works and every work of a series any of them was of, till no series of them is new
        val seen = HashSet<Long>()
        var fresh: Set<Long> = works.toSet()
        var read: TypeSeries
        do {
            seen += fresh
            read = TypeSeries.around(type, seen)
            val joined = read.all.flatMapTo(HashSet()) { it.works } + seen
            fresh = repository.worksOf(repository.ofWorks(joined).values).values.flatten().toSet() + joined - seen
        } while (fresh.isNotEmpty())
        val was = repository.ofWorks(seen)
        val before = repository.worksOf(was.values)
        val taken = HashSet<Long>()
        val columns = SeriesColumns(type)
        val made =
            read.all
                .sortedByDescending { it.works.size }
                .map { s ->
                    val id =
                        s.works
                            .mapNotNull(was::get)
                            .filter { it !in taken }
                            .groupingBy { it }
                            .eachCount()
                            .maxWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenByDescending { it.key })
                            ?.key
                    id?.let(taken::add)
                    columns.of(s, id, worksChanged = id == null || before[id] != s.works)
                }
        val gone = before.keys - taken
        val ids = repository.write(made, gone, now)
        // A series gone: its manner of no work now
        if (gone.isNotEmpty()) type.groupManners.remove(MannerGroup.SERIES, gone.map { "$it" })
        // A work of another series now, or of none: its feature of the series changed
        val inSeries = made.flatMapTo(HashSet()) { it.works }
        val moved =
            made.zip(ids).flatMap { (m, id) -> m.works.filter { was[it] != id } } + was.keys.filter { it !in inSeries }
        if (moved.isNotEmpty()) type.works.worksChanged(moved, WorkDatum.GROUPS, now)
    }

    /** Items of a source with series written: their works' series made anew, before anything reads them. */
    @Order(AFTER_WORKS)
    @EventListener
    fun itemsWritten(event: SiteItemWriteEvent) {
        val s = stores.source(event.source) ?: return
        if (seriesFacetOf(s) == null) return
        val type = stores.typeOf(event.source)
        refresh(type, type.works.worksOf(event.ids.map { ItemKey(s.id, it) }).values)
    }

    /** Works merged, taken apart, put in a series of the user's or out of it: their series made anew. */
    @EventListener
    fun regrouped(event: WorkGroupChangeEvent) {
        stores.type(event.type)?.let { refresh(it, event.works) }
    }

    /** A page of series of a site read: its title, whether it is to be visited, made anew of the series it is of. */
    @EventListener
    fun pagesRead(event: SiteSeriesPageChangeEvent) {
        val type = stores.typeOf(event.source)
        val series = type.series.ofSites(event.keys.map { SiteSeriesRef(event.source, it) }).values
        refresh(type, type.series.worksOf(series).values.flatten())
    }

    private companion object {
        /** Among the listeners of changed items: after the works are bound (-200) and the authors (-100). */
        const val AFTER_WORKS = -50
    }
}

/** Pages of the series [keys] of the site [source] read: what they say of them written. */
data class SiteSeriesPageChangeEvent(val source: String, val keys: List<String>)
