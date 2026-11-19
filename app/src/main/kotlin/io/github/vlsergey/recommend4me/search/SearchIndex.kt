package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.api.model.WorkKind
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

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        workload.resumption.resumes(UPDATING) { left, reason ->
            left.work.type?.let { planUpdate(it, Instant.now(), reason) }
        }
    }

    /**
     * The index of a type brought to every work when the plugin's is not of its format or holds nothing
     * ([SearchProvider.current][io.github.vlsergey.recommend4me.search.SearchProvider.current]): every source of it
     * waiting whole — what the data say, nothing for the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val provider = plugins.search ?: return
        // The scores of another reranker are of another model: read again as asked for
        plugins.reranker()?.let { r -> stores.types.forEach { it.searchScores.keepOnly(r.id) } }
        stores.types
            .filter { !provider.current(it.id) }
            .forEach { type ->
                workload.keys.want(updating(type.id), type.sources.map { whole(it.id) })
                planUpdate(type.id, Instant.now(), "searchFormat")
            }
    }

    /**
     * The items written since they were indexed, indexed now, in the queue of the computations: those the queue keeps
     * waiting ([WorkKeys.wanted]), done once written. Of every source of the type waiting whole, the index brought to
     * every work at once ([SearchProvider.sync][io.github.vlsergey.recommend4me.search.SearchProvider.sync]): of a
     * plugin that keeps it between the runs, only what changed written again, the works gone removed.
     */
    private fun catchUp(type: TypeStore) {
        val provider = plugins.search ?: return
        val work = updating(type.id)
        val asked = workload.keys.wanted(work)
        if (asked.isEmpty()) return
        val started = System.currentTimeMillis()
        val written =
            if (type.sources.all { whole(it.id) in asked }) {
                val prints = documents.printsOf(type)
                provider.sync(type.id, prints.prints) { keys -> documents.documentsOf(type, keys) }
                prints.prints.size
            } else written(provider, type, asked)
        workload.keys.done(work, asked)
        log.info(
            "{}: the search index caught up with {} works written, {} documents made again, in {} ms",
            type.id,
            asked.size,
            written,
            System.currentTimeMillis() - started,
        )
    }

    /** The items [asked] of the [type] written again, of a source asked whole every item of it; how many documents. */
    private fun written(provider: SearchProvider, type: TypeStore, asked: List<String>): Int =
        asked.groupBy({ it.substringBefore(SEP) }, { it.substringAfter(SEP) }).entries.sumOf { (source, ids) ->
            val s = stores.source(source)
            // Of a source gone, its items taken out; of a whole source, every item of it
            val keys =
                if (s != null && WHOLE in ids) s.items.keys().map { ItemKey(source, it.id) }
                else ids.filter { it != WHOLE }.map { ItemKey(source, it) }
            if (s == null) provider.update(type.id, emptyMap(), { emptyList() }, keys)
            else sources.written(provider, type.id, s, keys)
        }

    private companion object {
        val UPDATING = WorkKind.SEARCH_UPDATE

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
