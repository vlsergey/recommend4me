package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.SiteItemRemoveEvent
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskKeys
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(SearchIndex::class.java)

/**
 * THE INDEX OF THE SEARCH, the search plugin's, kept of the works there are: the documents of the works are written by
 * the tasks over the works ([WorkTexts]); the document of a site's item removed is taken out here, in the background,
 * shortly after the removals go quiet — a search never waits for it. The items waiting are kept in the queue
 * ([TaskKeys.want]): a stop leaves them to the next start.
 */
@Service
class SearchIndex(
    private val stores: Stores,
    private val plugins: Plugins,
    private val tasks: Tasks,
) {
    /** A site's item removed: its document taken out of the index of the search. */
    @EventListener
    fun removed(event: SiteItemRemoveEvent) {
        tasks.keys.want(removing(event.type), listOf("${event.source}$SEP${event.itemId}"))
        plan(event.type, Instant.now().plusMillis(QUIET_MS), "removal")
    }

    private fun plan(type: String, due: Instant, reason: String) =
        tasks.plan(removing(type), due, reason) { removeWaiting(type) }

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(KIND) { left, reason -> left.work.type?.let { plan(it, Instant.now(), reason) } }
    }

    /** The scores of another reranker are of another model: read again as asked for. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        plugins.reranker()?.let { r -> stores.types.forEach { it.searchScores.keepOnly(r.id) } }
    }

    /**
     * The documents of the items removed the queue keeps waiting ([TaskKeys.wanted]) taken out, done once taken out.
     */
    private fun removeWaiting(type: String) {
        val provider = plugins.search ?: return
        val work = removing(type)
        val waiting = tasks.keys.wanted(work)
        if (waiting.isEmpty()) return
        val keys = waiting.map { ItemKey(it.substringBefore(SEP), it.substringAfter(SEP)) }
        provider.update(type, emptyMap(), { emptyList() }, keys)
        tasks.keys.done(work, waiting)
        log.info("{}: {} documents of items removed taken out of the search", type, keys.size)
    }

    private companion object {
        val KIND = TaskKind.REMOVE_SITE_ITEM_TEXTS

        /** The key of an item waiting in the queue: its source and its id. */
        const val SEP = '\t'

        fun removing(type: String) = Task(KIND, type = type)

        /** The quiet after the last removal of an item before the documents are taken out. */
        const val QUIET_MS = 2000L
    }
}
