package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(Works::class.java)

/**
 * Which items are one work: the items of a normalized work of several ([WorkRepository]). An item of
 * no other is a work alone. Read at the start of every operation that needs it — such works are few,
 * made by hand.
 */
class WorkClusters(private val clusters: Map<ItemKey, List<ItemKey>>) {

    /** The items that are the same work as [key], [key] first; the item alone when it has no link. */
    fun members(key: ItemKey): List<ItemKey> = clusters[key]?.let { listOf(key) + (it - key) } ?: listOf(key)

    /** The work an item is of: the same number for every item of a work. */
    fun work(key: ItemKey): Long = Vectors.keyOf(representative(key).toString())

    /** The item that names the work: the first of its members by key. Every member keeps its own card. */
    fun representative(key: ItemKey): ItemKey = clusters[key]?.first() ?: key

    fun linked(key: ItemKey): Boolean = key in clusters

    /** Every work of more than one item: its items. */
    fun linkedWorks(): List<List<ItemKey>> = clusters.values.distinct()

    companion object {
        fun of(groups: List<List<ItemKey>>): WorkClusters =
            WorkClusters(groups.map { members -> members.sortedBy { it.toString() } }.flatMap { members -> members.map { it to members } }.toMap())
    }
}

@Component
class Works(private val stores: Stores) {
    /** The works of several items of a content type, across its sources and within each. */
    fun of(type: TypeStore): WorkClusters = WorkClusters.of(type.links.groups())

    /**
     * How much each of the items [keys] says of its work: the characters of its texts, then how many
     * values its facets have. Of a work of several items the one that says the most is the one shown
     * for it. Read for the operation: such works are few, and what a site says of them changes.
     */
    fun fullness(type: TypeStore, keys: Collection<ItemKey>): Map<ItemKey, Long> {
        val out = HashMap<ItemKey, Long>()
        keys.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            val s = type.source(source) ?: return@forEach
            val texts = s.items.textsOf(ids)
            val facets = s.items.facetsOf(ids)
            ids.forEach { id ->
                val chars = texts[id].orEmpty().values.sumOf { it.length.toLong() }
                out[ItemKey(source, id)] = chars * FIELDS_AT_MOST + facets[id].orEmpty().values.sumOf { it.size.toLong() }.coerceAtMost(FIELDS_AT_MOST - 1)
            }
        }
        return out
    }

    /** The [items] of the source — id to version — bound if not yet, their versions kept; one at a time: two at once would bind one item twice. */
    @Synchronized
    private fun bind(s: SourceStore, items: Map<String, String>) {
        val bound = s.works.boundAmong(s.id, items.keys)
        items.filter { (id, version) -> bound[id].let { it != null && it != version } }.forEach { (id, version) -> s.works.setVersion(ItemKey(s.id, id), version) }
        val fresh = items - bound.keys
        if (fresh.isEmpty()) return
        s.works.bindNew(s.id, fresh, Instant.now())
        log.info("{}: {} items bound to normalized works of their own", s.id, fresh.size)
    }

    /** The items [event] names that are there bound at once, before anything reads the works. */
    @Order(-200)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val s = stores.source(event.source) ?: return
        bind(s, s.items.heads(event.ids).mapValues { it.value.version })
    }

    /** Before anything reads the works: every item bound, and what the corrections and the ratings kept of the works given to them. */
    @Order(-200)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s -> bind(s, s.items.keys().associate { it.id to it.version }) }
        val now = Instant.now()
        stores.types.forEach { type ->
            val moved = type.links.moveToWorks(now)
            if (moved > 0) log.info("{}: {} links and words on works moved to the normalized works", type.id, moved)
            val grades = type.sources.sumOf { it.ratings.moveToWorks(now) }
            val marks = type.sources.sumOf { it.interest.moveToWorks(now) }
            if (grades > 0 || marks > 0) {
                type.links.keepNewestGrades()
                log.info("{}: {} grades and {} marks of interest moved to the normalized works", type.id, grades, marks)
            }
            val answers = type.sources.sumOf { it.corrections.moveToWorks(now) + it.unknowns.moveToWorks() }
            if (answers > 0) log.info("{}: {} corrections moved to the normalized works", type.id, answers)
        }
    }

    companion object {
        /** Above any number of values a work has: the characters of its texts come first. */
        private const val FIELDS_AT_MOST = 100_000L
    }
}
