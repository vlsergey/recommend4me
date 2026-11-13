package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.encoder.TextKind
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.vector.Vectors
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

/**
 * A line the user judged snippets of, as searched now: what was found, with every work's features, and of the works
 * found the user's word on the snippet each is shown with now ([SearchSnippet.judgedShown]) — a work shown with another
 * than the one judged is not judged.
 */
class SearchedLine(val found: SearchResult, val judged: Map<ItemKey, SearchFit>) {
    /** The works judged that were found, with their features: what the order is learnt of. */
    fun judgedLine(): JudgedLine =
        JudgedLine(
            found.line,
            found.keys.mapNotNull { key -> judged[key]?.let { found.features[key].orEmpty() to it } },
        )
}

/**
 * WHAT A SEARCH LEARNS OF THE USER'S JUDGMENTS LEARNT ANEW whenever the user judges a snippet shown ([SearchJudged]) —
 * shortly after the judgments go quiet — and on a start, in the queue of the computations: the correction of the
 * closeness by meaning ([QueryShift]) of the lines' vectors and the snippets judged; then every line judged searched
 * again with it and the reranker, and the weights of the features of its works ([SearchWeights]) learnt of the works
 * shown with a snippet judged. Both kept; a search reads them, it never waits for them.
 */
@Component
class SearchLearning(
    private val stores: Stores,
    private val search: Search,
    private val workload: Workload,
    private val plugins: Plugins,
    private val textVectors: TextVectors,
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

    /**
     * The correction of the closeness by meaning and the weights of the order of the [type] learnt of every line judged
     * and kept; none kept of lines with no pair.
     */
    private fun learnt(type: TypeStore) {
        val started = System.currentTimeMillis()
        val encoder = plugins.textEncoder()
        val shift = QueryShift.fit(snippetLines(type).values.toList())
        encoder?.let { type.searchShift.keep(shift, it.id, Instant.now()) }
        val lines = lines(type, languages.first(), shift).map { it.judgedLine() }
        val weights = SearchWeights.fit(lines)
        type.searchWeights.keep(weights, Instant.now())
        log.info(
            "{}: the search learnt of {} lines, {} works shown judged, in {} ms: the closeness by meaning {}; order {}",
            type.id,
            lines.size,
            lines.sumOf { it.works.size },
            System.currentTimeMillis() - started,
            shift?.let { "corrected, |a| %.3f, |b| %.3f".format(length(it.a), length(it.b)) } ?: "as the encoder's",
            weights?.features?.entries?.joinToString { (name, f) -> "$name %.3f".format(f.weight) } ?: "as found",
        )
    }

    /**
     * Every line of the [type] the user judged snippets of, searched now as the [language] with the closeness by
     * meaning corrected by [shift], if any, and the reranker, in the order of what it said — not of the weights learnt:
     * what they are learnt of.
     */
    fun lines(type: TypeStore, language: String, shift: QueryShift?): List<SearchedLine> =
        type.searchJudgments.all().toSortedMap().mapNotNull { (query, judged) ->
            search.search(type.id, query, language, with = SearchLearnt(shift, null))?.let { found ->
                SearchedLine(found, search.judgedShown(type.id, found, judged))
            }
        }

    /**
     * Every line of the [type] the user judged snippets of, by the line: its vector, and of every snippet judged its
     * vector, of unit length — what the correction of the closeness by meaning is learnt of. The snippets encoded as
     * passages, every one of every line in one go; none without a text encoder.
     */
    fun snippetLines(type: TypeStore): Map<String, SnippetLine> {
        val encoder = plugins.textEncoder() ?: return emptyMap()
        val judged = type.searchJudgments.all().toSortedMap()
        val texts = judged.values.flatMap { works -> works.values.flatMap { it.keys } }.map { it.text }.distinct()
        val vectors =
            if (texts.isEmpty()) emptyMap()
            else
                texts
                    .zip(encoder.encode(texts, TextKind.PASSAGE))
                    .mapNotNull { (t, v) -> v?.let { t to Vectors.unit(it) } }
                    .toMap()
        return judged
            .mapNotNull { (query, works) ->
                textVectors.query(query)?.let { q ->
                    val snippets =
                        works.values
                            .flatMap { it.entries }
                            .mapNotNull { (s, fit) -> vectors[s.text]?.let { it to fit } }
                    query to SnippetLine(Vectors.unit(q), snippets)
                }
            }
            .toMap()
    }

    /** When the correction of the closeness by meaning of the [type] kept was learnt, of the text encoder of now. */
    fun shiftedAt(type: TypeStore): Instant? = plugins.textEncoder()?.let { type.searchShift.read(it.id)?.second }

    private fun length(v: FloatArray) = kotlin.math.sqrt(v.sumOf { it.toDouble() * it })

    @PreDestroy fun shutdown() = timer.shutdownNow()

    private companion object {
        /** The quiet after the last judgment before the order is learnt again: the user judges a page in a row. */
        const val QUIET_MS = 5000L
    }
}
