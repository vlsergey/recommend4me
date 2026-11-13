package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SearchLearning::class.java)

/** A line the user judged works of, as searched now: what was found, with every work's features, and the judgments. */
class SearchedLine(val found: SearchResult, val judged: Map<ItemKey, Boolean>) {
    /** The works judged that were found, with their features: what the order is learnt of. */
    fun judgedLine(): JudgedLine =
        JudgedLine(
            found.line,
            found.keys.mapNotNull { key -> judged[key]?.let { found.features[key].orEmpty() to it } },
        )
}

/**
 * THE ORDER OF WHAT A SEARCH FINDS LEARNT ANEW whenever the user judges a work found ([SearchJudged]) — shortly after
 * the judgments go quiet — and on a start, in the queue of the computations: every line judged searched again, with the
 * reranker, and the weights of the features of its works ([SearchWeights]) learnt of them and kept. A search reads the
 * weights kept, it never waits for them.
 */
@Component
class SearchLearning(
    private val stores: Stores,
    private val search: Search,
    private val workload: Workload,
    /** The user's languages: the lines judged are searched again as the first. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) {
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "search-learning").apply { isDaemon = true }
    }
    private val planned = ConcurrentHashMap<String, ScheduledFuture<*>>()

    @EventListener fun judged(event: SearchJudged) = plan(event.type, "searchJudged")

    /** On a start: the weights of every type with judgments learnt again — the reranker may be another. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() = stores.types.filter { it.searchJudgments.all().isNotEmpty() }.forEach { plan(it.id, "start") }

    /** The weights of the [type] learnt shortly after the last of a quick series of judgments. */
    private fun plan(type: String, reason: String) {
        planned.compute(type) { _, old ->
            old?.cancel(false)
            workload.plan(work(type), Instant.now().plusMillis(QUIET_MS), reason)
            timer.schedule({ learn(type) }, QUIET_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun work(type: String) = Work("searchWeights", type = type)

    // The timer's task: whatever fails is told in the log, and the next judgment plans the learning again
    @Suppress("TooGenericExceptionCaught")
    private fun learn(type: String) {
        try {
            val store = stores.type(type) ?: return
            workload.run(work(type)) { learnt(store) }
        } catch (e: Exception) {
            log.error("{}: the order of the search was not learnt", type, e)
        }
    }

    /** The weights of the [type] learnt of every line judged and kept; none kept of lines with no pair. */
    private fun learnt(type: TypeStore) {
        val started = System.currentTimeMillis()
        val lines = lines(type, languages.first()).map { it.judgedLine() }
        val weights = SearchWeights.fit(lines)
        type.searchWeights.keep(weights, Instant.now())
        log.info(
            "{}: the order of the search learnt of {} lines, {} works judged, in {} ms: {}",
            type.id,
            lines.size,
            lines.sumOf { it.works.size },
            System.currentTimeMillis() - started,
            weights?.features?.entries?.joinToString { (name, f) -> "$name %.3f".format(f.weight) } ?: "none",
        )
    }

    /**
     * Every line of the [type] the user judged works of, searched now as the [language] with the reranker, in the order
     * of what it said — not of the weights learnt: what they are learnt of.
     */
    fun lines(type: TypeStore, language: String): List<SearchedLine> =
        type.searchJudgments.all().toSortedMap().mapNotNull { (query, judged) ->
            search.search(type.id, query, language, rerank = true, learnt = false)?.let { SearchedLine(it, judged) }
        }

    @PreDestroy fun shutdown() = timer.shutdownNow()

    private companion object {
        /** The quiet after the last judgment before the order is learnt again: the user judges a page in a row. */
        const val QUIET_MS = 5000L
    }
}
