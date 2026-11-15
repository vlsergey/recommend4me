package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.FacetChancesChanged
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(SearchIndex::class.java)

/**
 * THE INDEX OF THE SEARCH, the search plugin's, brought to every document ([SearchDocuments]): on every start, in the
 * background — of a plugin that keeps it between the runs, only what changed written again ([SearchProvider.sync]); an
 * item written since it was indexed is indexed again in the background too, shortly after the writes go quiet — a
 * search never waits for it, it searches the index as it is (the queue of their keys is all that is kept here).
 */
@Service
class SearchIndex(
    private val stores: Stores,
    private val plugins: Plugins,
    private val documents: SearchDocuments,
    private val workload: Workload,
) {
    private val pending = ConcurrentHashMap<String, MutableSet<ItemKey>>()
    private val built = ConcurrentHashMap<String, CountDownLatch>()
    private val sources = SourceCatchUp(documents)

    @EventListener fun itemsChanged(event: ItemsChanged) = queue(event.source, event.ids)

    @EventListener fun vectorsChanged(event: TextVectorsChanged) = queue(event.source, event.ids)

    @EventListener fun chancesChanged(event: FacetChancesChanged) = queue(event.source, event.ids)

    /** The user's answer on a work's value: the work is found by what the user said it has. */
    @EventListener fun answered(event: FacetAnswered) = queue(event.source, listOf(event.itemId))

    /**
     * The items written are indexed again in the background, in the queue of the computations, shortly after the last
     * of a quick series of writes; a failure told in the log, the next write plans the update again.
     */
    private fun queue(source: String, ids: Collection<String>) {
        val type = stores.typeOf(source).id
        pending.computeIfAbsent(type) { ConcurrentHashMap.newKeySet() } += ids.map { ItemKey(source, it) }
        workload.plan(Work("searchUpdate", type = type), Instant.now().plusMillis(UPDATE_QUIET_MS), "items") {
            stores.type(type)?.let(::catchUp)
        }
    }

    /**
     * The index of every type made on start, in the queue of the computations, each a work of its own, and what was
     * written meanwhile caught up with; a type whose index fails is told in the log, the others made all the same.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun build() {
        if (plugins.search == null) return
        // The scores of another reranker are of another model: read again as asked for
        plugins.reranker()?.let { r -> stores.types.forEach { it.searchScores.keepOnly(r.id) } }
        stores.types.forEach { built[it.id] = CountDownLatch(1) }
        stores.types.forEach { type ->
            workload.plan(Work("searchIndex", type = type.id), null, "start") {
                made(type)
                // What was written while the index was made
                catchUp(type)
            }
        }
    }

    /** The index of the start made, unless it is made already. */
    private fun made(type: TypeStore) {
        val provider = plugins.search ?: return
        val latch = built[type.id] ?: return
        try {
            // Kept between the runs: the documents of the works whose fingerprint changed since alone made and written
            // again, of the same values of the model their fingerprints were made of
            if (latch.count > 0) {
                val prints = documents.printsOf(type)
                provider.sync(type.id, prints.prints) { keys -> documents.documentsOf(type, keys, prints.chances) }
            }
        } finally {
            latch.countDown()
        }
    }

    /**
     * The items written since they were indexed, indexed now, in the queue of the computations — once the index of the
     * start is made.
     */
    private fun catchUp(type: TypeStore) {
        val provider = plugins.search
        val building = built[type.id]?.let { it.count > 0 } == true
        if (provider == null || building) return
        val keys = pending[type.id]?.let { set -> set.toList().also { set.removeAll(it.toSet()) } }.orEmpty()
        if (keys.isEmpty()) return
        val started = System.currentTimeMillis()
        val written =
            keys
                .groupBy { it.source }
                .entries
                .sumOf { (source, list) ->
                    val s = stores.source(source)
                    if (s == null) provider.update(type.id, emptyMap(), { emptyList() }, list)
                    else sources.written(provider, type.id, s, list)
                }
        log.info(
            "{}: the search index caught up with {} works written, {} documents made again, in {} ms",
            type.id,
            keys.size,
            written,
            System.currentTimeMillis() - started,
        )
    }

    private companion object {
        /**
         * The quiet after the last write of an item before the items written are indexed again: a page is sent several
         * times as it grows.
         */
        const val UPDATE_QUIET_MS = 2000L
    }
}
