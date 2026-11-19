package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.WorkKeys
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
 * THE INDEX OF THE SEARCH, the search plugin's, brought to every document ([SearchDocuments]): made whole in the
 * background when the plugin's index is not of its format or holds nothing ([SearchProvider.current]) — of a plugin
 * that keeps it between the runs, only what changed written again ([SearchProvider.sync]); an item written since it was
 * indexed is indexed again in the background too, shortly after the writes go quiet — a search never waits for it, it
 * searches the index as it is. The items waiting are kept in the queue ([WorkKeys.want]): a stop leaves them to the
 * next start, which indexes them and nothing more (owner, 2026-11-19: "nothing is to be run at a start just because it
 * is a start" — the index of the books, 16 minutes, was brought to every work at every start).
 */
@Service
class SearchIndex(
    private val stores: Stores,
    private val plugins: Plugins,
    private val documents: SearchDocuments,
    private val workload: Workload,
) {
    private val built = ConcurrentHashMap<String, CountDownLatch>()
    private val sources = SourceCatchUp(documents)

    @EventListener fun itemsChanged(event: ItemsChanged) = queue(event.source, event.ids, "items")

    @EventListener fun vectorsChanged(event: TextVectorsChanged) = queue(event.source, event.ids, "texts")

    /** The user's answer on a work's value: the work is found by what the user said it has. */
    @EventListener fun answered(event: FacetAnswered) = queue(event.source, listOf(event.itemId), "answer")

    /**
     * The items written are indexed again in the background, in the queue of the computations, shortly after the last
     * of a quick series of writes — of no [ids], every item of the source; a failure told in the log, the items kept
     * waiting for the next write's update.
     */
    private fun queue(source: String, ids: Collection<String>, reason: String) {
        val type = stores.typeOf(source).id
        workload.keys.want(updating(type), if (ids.isEmpty()) listOf(whole(source)) else ids.map { keyOf(source, it) })
        planUpdate(type, Instant.now().plusMillis(UPDATE_QUIET_MS), reason)
    }

    private fun planUpdate(type: String, due: Instant, reason: String) =
        workload.plan(updating(type), due, reason) { stores.type(type)?.let(::catchUp) }

    /**
     * Of what the last run left undone, the same planned again: the updates of the items kept waiting, the index made
     * whole; and the index of a type made whole when the plugin's is not of its format or holds nothing — nothing for
     * the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun build() {
        val left = workload.left(BUILDING).mapNotNull { it.work.type }.toSet()
        val updates = workload.left(UPDATING)
        val provider = plugins.search ?: return
        // The scores of another reranker are of another model: read again as asked for
        plugins.reranker()?.let { r -> stores.types.forEach { it.searchScores.keepOnly(r.id) } }
        stores.types.forEach { type ->
            val anew = type.id in left || !provider.current(type.id)
            built[type.id] = CountDownLatch(if (anew) 1 else 0)
            if (anew)
                workload.plan(Work(BUILDING, type = type.id), null, if (type.id in left) "sweep" else "searchFormat") {
                    made(type)
                    // What was written while the index was made
                    catchUp(type)
                }
        }
        updates.forEach { u -> u.work.type?.let { t -> u.reasons.keys.forEach { planUpdate(t, Instant.now(), it) } } }
    }

    /** The index of the [type] made whole, unless it is made already. */
    private fun made(type: TypeStore) {
        val provider = plugins.search ?: return
        val latch = built[type.id] ?: return
        try {
            // Kept between the runs: the documents of the works whose fingerprint changed since alone made and written
            // again
            if (latch.count > 0) {
                val prints = documents.printsOf(type)
                provider.sync(type.id, prints.prints) { keys -> documents.documentsOf(type, keys) }
            }
        } finally {
            latch.countDown()
        }
    }

    /**
     * The items written since they were indexed, indexed now, in the queue of the computations — once the index is made
     * whole: those the queue keeps waiting ([WorkKeys.wanted]), done once written.
     */
    private fun catchUp(type: TypeStore) {
        val provider = plugins.search
        val building = built[type.id]?.let { it.count > 0 } == true
        if (provider == null || building) return
        val work = updating(type.id)
        val asked = workload.keys.wanted(work)
        if (asked.isEmpty()) return
        val started = System.currentTimeMillis()
        val bySource = asked.groupBy({ it.substringBefore(SEP) }, { it.substringAfter(SEP) })
        val written =
            bySource.entries.sumOf { (source, ids) ->
                val s = stores.source(source)
                // Of a source gone, its items taken out; of a whole source, every item of it
                val keys =
                    if (s != null && WHOLE in ids) s.items.keys().map { ItemKey(source, it.id) }
                    else ids.filter { it != WHOLE }.map { ItemKey(source, it) }
                if (s == null) provider.update(type.id, emptyMap(), { emptyList() }, keys)
                else sources.written(provider, type.id, s, keys)
            }
        workload.keys.done(work, asked)
        log.info(
            "{}: the search index caught up with {} works written, {} documents made again, in {} ms",
            type.id,
            asked.size,
            written,
            System.currentTimeMillis() - started,
        )
    }

    private companion object {
        const val BUILDING = "searchIndex"
        const val UPDATING = "searchUpdate"

        /** The key of an item waiting in the queue: its source and its id. */
        const val SEP = '\t'

        /** The id that stands for every item of its source. */
        const val WHOLE = ""

        fun updating(type: String) = Work(UPDATING, type = type)

        fun keyOf(source: String, id: String) = "$source$SEP$id"

        fun whole(source: String) = keyOf(source, WHOLE)

        /**
         * The quiet after the last write of an item before the items written are indexed again: a page is sent several
         * times as it grows.
         */
        const val UPDATE_QUIET_MS = 2000L
    }
}
