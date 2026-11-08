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
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search-index").apply { isDaemon = true } }
    private val updater = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "search-update").apply { isDaemon = true }
    }
    private val updates = ConcurrentHashMap<String, ScheduledFuture<*>>()

    @EventListener fun itemsChanged(event: ItemsChanged) = queue(event.source, event.ids)

    @EventListener fun vectorsChanged(event: TextVectorsChanged) = queue(event.source, event.ids)

    @EventListener fun chancesChanged(event: FacetChancesChanged) = queue(event.source, event.ids)

    /** The user's answer on a work's value: the work is found by what the user said it has. */
    @EventListener fun answered(event: FacetAnswered) = queue(event.source, listOf(event.itemId))

    /** The items written are indexed again in the background, shortly after the last of a quick series of writes. */
    private fun queue(source: String, ids: Collection<String>) {
        val type = stores.typeOf(source).id
        pending.computeIfAbsent(type) { ConcurrentHashMap.newKeySet() } += ids.map { ItemKey(source, it) }
        updates.compute(type) { _, old ->
            old?.cancel(false)
            workload.plan(updating(type), Instant.now().plusMillis(UPDATE_QUIET_MS), "items")
            updater.schedule({ update(type) }, UPDATE_QUIET_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** The items written since they were indexed, of the type [type], indexed: a task of the background. */
    // The updater's loop: whatever fails is told in the log, and the next write plans the update again
    @Suppress("TooGenericExceptionCaught")
    private fun update(type: String) {
        try {
            stores.type(type)?.let(::catchUp)
        } catch (e: Exception) {
            log.error("{}: the search index was not updated", type, e)
        }
    }

    private fun updating(type: String) = Work("searchUpdate", type = type)

    @EventListener(ApplicationReadyEvent::class)
    // The worker's pass on start: a type whose index fails any way is told in the log, the others made all the same
    @Suppress("TooGenericExceptionCaught")
    fun build() {
        if (plugins.search == null) return
        // The scores of another reranker are of another model: read again as asked for
        plugins.reranker()?.let { r -> stores.types.forEach { it.searchScores.keepOnly(r.id) } }
        stores.types.forEach { built[it.id] = CountDownLatch(1) }
        worker.execute {
            stores.types.forEach { type ->
                try {
                    made(type, mapOf("start" to 1))
                } catch (e: Exception) {
                    log.error("{}: the search index was not made", type.id, e)
                }
                // What was written while the index was made
                catchUp(type)
            }
        }
    }

    /** The index of the start made, in the queue of the computations, unless it is made already. */
    private fun made(type: TypeStore, reasons: Map<String, Int>) {
        val provider = plugins.search ?: return
        val latch = built[type.id] ?: return
        try {
            workload.run(Work("searchIndex", type = type.id), reasons) {
                // Kept between the runs: the documents of the works whose fingerprint changed since alone made and
                // written again, of the same values of the model their fingerprints were made of
                if (latch.count > 0) {
                    val prints = documents.printsOf(type)
                    provider.sync(type.id, prints.prints) { keys -> documents.documentsOf(type, keys, prints.chances) }
                }
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
        val reasons = workload.take(updating(type.id))
        val keys = pending[type.id]?.let { set -> set.toList().also { set.removeAll(it.toSet()) } }.orEmpty()
        if (keys.isEmpty()) return
        // The works taken here are this update's own: another update is of others
        workload.run(updating(type.id), reasons, joinAlike = false) {
            // A chunk at a time: the chapters and the vectors of thousands of works — a refit's, a new encoder's — are
            // not held at once
            keys
                .groupBy { it.source }
                .forEach { (source, list) ->
                    list.chunked(CHUNK).forEach { chunk ->
                        val docs: Map<ItemKey, SearchDocument> =
                            stores.source(source)?.let { documents.documents(it, chunk.map { k -> k.id }) }.orEmpty()
                        provider.update(type.id, docs.values.toList(), chunk.filter { it !in docs })
                    }
                }
        }
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
        updater.shutdownNow()
    }

    private companion object {
        /**
         * The quiet after the last write of an item before the items written are indexed again: a page is sent several
         * times as it grows.
         */
        const val UPDATE_QUIET_MS = 2000L

        /** The works indexed again at a time: memory, not meaning. */
        const val CHUNK = 500
    }
}
