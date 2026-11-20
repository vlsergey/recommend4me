package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.SiteItemWriteEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.work.WorkGroupChangeEvent
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SeriesDifferences::class.java)

/**
 * THE WORD "DIFFERENT" ON TWO WORKS OF ONE SERIES TAKEN BACK — of one series by the sites, or by the user's words "one
 * series" one after another —: the series is stronger than the word (owner, 2026-11-20: "the site is stronger than a
 * hand-made 'not a series'"), and two works can be no pair of different works and of one series at once, nor teach the
 * models both. Taken back in the journal, told apart there from a word the user took back. Done in the queue after a
 * quiet, of every word "different" of the type — they are few — as the items of a site that has series are written and
 * as works are regrouped: merged, taken out, put in one series or taken out of it. Till then the word is not heeded: of
 * two works of one series it is read as "one series" ([io.github.vlsergey.recommend4me.duplicate.PairAnswers]).
 */
@Component
class SeriesDifferences(private val stores: Stores, private val tasks: Tasks) {

    // What the last run of the application left undone of it planned again by the queue
    init {
        tasks.resumption.resumes(WORK) { left, reason -> left.work.type?.let(stores::type)?.let { plan(it, reason) } }
    }

    @EventListener
    fun itemsWritten(event: SiteItemWriteEvent) {
        val s = stores.source(event.source) ?: return
        if (seriesFacetOf(s) != null) plan(stores.typeOf(event.source), "items")
    }

    @EventListener
    fun regrouped(event: WorkGroupChangeEvent) {
        stores.type(event.type)?.let { plan(it, "regrouped") }
    }

    private fun plan(type: TypeStore, reason: String) {
        // Due at once, no quiet: a quiet put off by every write of the sites' items never comes; planned again while it
        // waits its turn, it is that one
        tasks.plan(Task(WORK, type = type.id), Instant.now(), reason) { erase(type) }
    }

    /** Of every two works the user said to be different, those of one series: the word taken back. */
    fun erase(type: TypeStore) {
        val started = System.currentTimeMillis()
        val pairs = type.works.differentWorks()
        if (pairs.isEmpty()) return
        val series = TypeSeries.around(type, pairs.flatMap { listOf(it.first, it.second) })
        val within = pairs.filter { (x, y) -> series.ofWork(x)?.let { it === series.ofWork(y) } == true }
        val items = type.works.itemsOf(within.flatMap { listOf(it.first, it.second) })
        val now = Instant.now()
        var taken = 0
        within.forEach { (x, y) ->
            val a = items[x]?.firstOrNull() ?: return@forEach
            val b = items[y]?.firstOrNull() ?: return@forEach
            type.links.takeBack(a, b, now, ofSeries = true)
            taken++
        }
        log.info(
            "{}: of {} words \"different\", {} on two works of one series taken back, in {} ms",
            type.id,
            pairs.size,
            taken,
            System.currentTimeMillis() - started,
        )
    }

    companion object {
        /** The work of the queue taking the words back. */
        val WORK = TaskKind.ERASE_DIFFERENT_IN_SERIES

        /**
         * Of the [pairs] of items, those whose works are held in one series by the sites alone — whatever the user's
         * groups —: a word "different" on them would be taken back at once. The series read once around them all.
         */
        fun heldBySites(type: TypeStore, pairs: Collection<Pair<ItemKey, ItemKey>>): Set<Pair<ItemKey, ItemKey>> =
            ofOneSeries(type, pairs, bySites = true)

        /**
         * Of the [pairs] of items, those whose works are of one series — by the sites, or by the user's words "one
         * series" —; of the sites' alone of [bySites]. The series read once around them all.
         */
        fun ofOneSeries(
            type: TypeStore,
            pairs: Collection<Pair<ItemKey, ItemKey>>,
            bySites: Boolean = false,
        ): Set<Pair<ItemKey, ItemKey>> {
            if (pairs.isEmpty()) return emptySet()
            val works = type.works.worksOf(pairs.flatMap { listOf(it.first, it.second) })
            val series = TypeSeries.around(type, works.values, bySites)
            return pairs.filterTo(HashSet()) { (a, b) ->
                val x = works[a]
                val y = works[b]
                x != null && y != null && x != y && series.ofWork(x)?.let { it === series.ofWork(y) } == true
            }
        }
    }
}
