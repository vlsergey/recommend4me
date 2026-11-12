package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.contenttype.StandardValue
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Works::class.java)

/**
 * Which items are one work: the items of a normalized work of several ([WorkRepository]), the one that says the most of
 * it first ([WorkRepository.groups]). An item of no other is a work alone. Read at the start of every operation that
 * needs it — such works are few, made by hand.
 */
class WorkClusters(private val clusters: Map<ItemKey, List<ItemKey>>) {
    /** The item that names each work of several: the first of its members by key. */
    private val representatives = clusters.mapValues { (_, members) -> members.minBy { it.toString() } }

    /**
     * The items that are the same work as [key], [key] first, the others saying the most first; [key] alone of none.
     */
    fun members(key: ItemKey): List<ItemKey> = clusters[key]?.let { listOf(key) + (it - key) } ?: listOf(key)

    /** The items that are the same work as [key], the one that says the most first; [key] alone of none. */
    fun fullestFirst(key: ItemKey): List<ItemKey> = clusters[key] ?: listOf(key)

    /** The item the work of [key] is shown with: the one that says the most of it. */
    fun shown(key: ItemKey): ItemKey = clusters[key]?.first() ?: key

    /** The work an item is of: the same number for every item of a work. */
    fun work(key: ItemKey): Long = Vectors.keyOf(representative(key).toString())

    /** The item that names the work: the first of its members by key. Every member keeps its own card. */
    fun representative(key: ItemKey): ItemKey = representatives[key] ?: key

    fun linked(key: ItemKey): Boolean = key in clusters

    /** Every work of more than one item: its items. */
    fun linkedWorks(): List<List<ItemKey>> = clusters.values.distinct()

    companion object {
        /** The works of several items as [groups] of them, each the one that says the most first. */
        fun of(groups: List<List<ItemKey>>): WorkClusters =
            WorkClusters(groups.flatMap { members -> members.map { it to members } }.toMap())
    }
}

@Component
class Works(private val stores: Stores) {
    /** The works of several items of a content type, across its sources and within each. */
    fun of(type: TypeStore): WorkClusters = WorkClusters.of(type.links.groups())

    /**
     * The order a work of several items is shown by them, of each of the items [ids] of the source, kept with its
     * binding ([WorkRepository.measured]): whether the site gives it a value the work is shown by first
     * ([StandardValue.shownFirst] — a finished book), then how much it says of the work — the characters of its texts,
     * then how many values its facets have, both counted by the database. The first of them is the one the work is
     * shown with ([WorkClusters.shown]).
     */
    private fun measure(s: SourceStore, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val chars = s.items.lengthsOf(ids)
        val values = s.items.valueCountsOf(ids)
        val first =
            s.schema.facets.flatMapTo(HashSet()) { facet ->
                val keys = facet.standard?.values.orEmpty().filter { it.shownFirst }.map { it.key }
                s.items.withAnyOf(ids, facet.key, keys)
            }
        s.works.measured(
            s.id,
            ids.associateWith { id ->
                (chars[id] ?: 0L) * FIELDS_AT_MOST + (values[id] ?: 0).toLong().coerceAtMost(FIELDS_AT_MOST - 1)
            },
            first,
        )
    }

    /**
     * The [items] of the source — id to version — bound if not yet, their versions kept; one at a time: two at once
     * would bind one item twice.
     */
    @Synchronized
    private fun bind(s: SourceStore, items: Map<String, String>) {
        val bound = s.works.boundAmong(s.id, items.keys)
        items
            .filter { (id, version) -> bound[id].let { it != null && it != version } }
            .forEach { (id, version) -> s.works.setVersion(ItemKey(s.id, id), version) }
        val fresh = items - bound.keys
        if (fresh.isEmpty()) return
        s.works.bindNew(s.id, fresh, Instant.now())
        log.info("{}: {} items bound to normalized works of their own", s.id, fresh.size)
    }

    /** The items [event] names that are there bound at once, before anything reads the works. */
    @Order(BIND_FIRST)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val s = stores.source(event.source) ?: return
        bind(s, s.items.heads(event.ids).mapValues { it.value.version })
        measure(s, event.ids)
    }

    /** Before anything reads the works: every item bound, and measured if it never was. */
    @Order(BIND_FIRST)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s ->
            bind(s, s.items.keys().associate { it.id to it.version })
            val unmeasured = s.works.unmeasured(s.id)
            measure(s, unmeasured)
            if (unmeasured.isNotEmpty()) log.info("{}: {} items measured for their works", s.id, unmeasured.size)
        }
    }

    companion object {
        /** Above any number of values a work has: the characters of its texts come first. */
        private const val FIELDS_AT_MOST = 100_000L

        /** The place of the binding among the listeners: the first, before anything reads the works. */
        private const val BIND_FIRST = -200
    }
}
